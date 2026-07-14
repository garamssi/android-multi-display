import Foundation
import ScreenCaptureKit
import CoreMedia
import CoreVideo

public final class SCKScreenCapturer: NSObject, ScreenCapturing, @unchecked Sendable {
    private let lock = NSLock()
    private var stream: SCStream?
    private var output: StreamOutput?
    private let sampleQueue = DispatchQueue(label: "com.desklink.sck.output", qos: .userInteractive)

    public override init() {
        super.init()
    }

    public func startCapture(displayID: UInt32, fps: Int) -> AsyncThrowingStream<VideoFrame, Error> {
        AsyncThrowingStream<VideoFrame, Error>(bufferingPolicy: .bufferingNewest(3)) { continuation in
            let task = Task { [weak self] in
                guard let self else {
                    continuation.finish(throwing: ConnectionError.displayCaptureFailed)
                    return
                }
                do {
                    try await self.beginCapture(displayID: displayID, fps: fps, continuation: continuation)
                } catch is CancellationError {
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }

            continuation.onTermination = { @Sendable _ in
                task.cancel()
                Task { [weak self] in await self?.stopCapture() }
            }
        }
    }

    public func stopCapture() async {
        let streamToStop = lock.withLock { () -> SCStream? in
            let s = self.stream
            self.stream = nil
            self.output = nil
            return s
        }
        if let streamToStop {
            try? await streamToStop.stopCapture()
        }
    }

    // MARK: - Private

    private func beginCapture(
        displayID: UInt32,
        fps: Int,
        continuation: AsyncThrowingStream<VideoFrame, Error>.Continuation
    ) async throws {
        // A freshly-created virtual display can take a moment to appear in SCShareableContent; poll briefly for the target displayID.
        Log.info(.capture, "capture: requested virtual displayID=\(displayID)")
        var display: SCDisplay?
        for attempt in 1...15 {
            try Task.checkCancellation()
            let content: SCShareableContent
            do {
                content = try await SCShareableContent.excludingDesktopWindows(
                    false,
                    onScreenWindowsOnly: false
                )
            } catch {
                Log.error(.capture, "capture: getShareableContent failed: \(error)")
                throw ConnectionError.displayCaptureFailed
            }
            if let match = content.displays.first(where: { $0.displayID == displayID }) {
                display = match
                break
            }
            let available = content.displays.map { $0.displayID }
            Log.info(.capture, "capture: displayID \(displayID) not found yet (attempt \(attempt)); available=\(available)")
            try await Task.sleep(nanoseconds: 200_000_000) // 200ms
        }

        guard let display else {
            Log.error(.capture, "capture: virtual display \(displayID) NOT found among shareable displays — giving up")
            throw ConnectionError.displayCaptureFailed
        }
        Log.info(.capture, "capture: found display \(displayID) size=\(display.width)x\(display.height); configuring SCStream")

        try Task.checkCancellation()

        let filter = SCContentFilter(display: display, excludingWindows: [])

        let configuration = SCStreamConfiguration()
        configuration.width = display.width
        configuration.height = display.height
        configuration.pixelFormat = kCVPixelFormatType_32BGRA
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(max(1, fps)))
        configuration.queueDepth = 5
        configuration.showsCursor = true
        configuration.scalesToFit = false

        let streamOutput = StreamOutput(continuation: continuation)
        let newStream = SCStream(filter: filter, configuration: configuration, delegate: streamOutput)

        do {
            try newStream.addStreamOutput(streamOutput, type: .screen, sampleHandlerQueue: sampleQueue)
        } catch {
            throw ConnectionError.displayCaptureFailed
        }

        lock.withLock {
            self.stream = newStream
            self.output = streamOutput
        }

        do {
            try await newStream.startCapture()
            Log.info(.capture, "capture: SCStream started")
        } catch {
            Log.error(.capture, "capture: startCapture failed: \(error)")
            lock.withLock {
                self.stream = nil
                self.output = nil
            }
            throw ConnectionError.displayCaptureFailed
        }
    }
}

// MARK: - Stream output

private final class StreamOutput: NSObject, SCStreamOutput, SCStreamDelegate, @unchecked Sendable {
    private let continuation: AsyncThrowingStream<VideoFrame, Error>.Continuation
    private var frameCount = 0
    private var skippedCount = 0

    init(continuation: AsyncThrowingStream<VideoFrame, Error>.Continuation) {
        self.continuation = continuation
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen else { return }
        guard sampleBuffer.isValid else { return }

        guard let attachmentsArray = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let attachments = attachmentsArray.first,
              let statusRaw = attachments[.status] as? Int,
              let status = SCFrameStatus(rawValue: statusRaw),
              status == .complete else {
            skippedCount += 1
            if skippedCount <= 3 {
                Log.debug(.capture, "capture: skipped non-complete frame (idle/blank) #\(skippedCount)")
            }
            return
        }

        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let timestampUs = pts.isValid ? Int64(CMTimeGetSeconds(pts) * 1_000_000) : 0

        CVPixelBufferLockBaseAddress(imageBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(imageBuffer, .readOnly) }

        guard let baseAddress = CVPixelBufferGetBaseAddress(imageBuffer) else { return }
        let width = CVPixelBufferGetWidth(imageBuffer)
        let height = CVPixelBufferGetHeight(imageBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(imageBuffer)
        let totalBytes = bytesPerRow * height
        let data = Data(bytes: baseAddress, count: totalBytes)

        let frame = VideoFrame(
            data: data,
            timestampUs: timestampUs,
            isKeyframe: false,
            width: width,
            height: height,
            bytesPerRow: bytesPerRow
        )
        frameCount += 1
        if frameCount <= 3 {
            Log.debug(.capture, "capture: yielded frame #\(frameCount) (\(width)x\(height), stride=\(bytesPerRow))")
        }
        continuation.yield(frame)
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        continuation.finish(throwing: error)
    }
}
