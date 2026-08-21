import Foundation
import ScreenCaptureKit
import CoreMedia
import CoreVideo

public final class SCKScreenCapturer: NSObject, ScreenCapturing, @unchecked Sendable {
    private let lock = NSLock()
    private var stream: SCStream?
    private var output: StreamOutput?
    // A freshly created virtual display takes a moment to appear in SCShareableContent.
    // The budget is generous rather than tight: on a loaded machine the wait is longer,
    // and giving up early surfaces as an outright capture failure.
    private static let captureQueueDepth = 5

    private static let displayDiscoveryAttempts = 25
    private static let displayDiscoveryIntervalNanos: UInt64 = 200_000_000

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
        for attempt in 1...Self.displayDiscoveryAttempts {
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
            try await Task.sleep(nanoseconds: Self.displayDiscoveryIntervalNanos) // 200ms
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
        // Frames buffered by ScreenCaptureKit before it drops. Deep enough to ride out a
        // scheduling hiccup, shallow enough that a stall does not deliver stale frames.
        configuration.queueDepth = Self.captureQueueDepth
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
    private var untimedFrameCount = 0

    // Puts SCK presentation timestamps on the shared audio/video axis, verifying by
    // measurement that they are already on it.
    private var clockAligner = ClockDomainAligner()

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

        // A frame with no usable presentation time is DROPPED, not stamped 0: zero is a
        // legal point on this axis (the boot instant), so a placeholder is indistinguishable
        // from a real stamp and would read to the aligner as an uptime-sized epoch mismatch,
        // corrupting every timestamp for the rest of the session.
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        guard let rawTimestampUs = MediaClock.microsFrom(pts) else {
            untimedFrameCount += 1
            if untimedFrameCount <= 3 {
                Log.error(.capture, "capture: dropped frame with no presentation timestamp (#\(untimedFrameCount))")
            }
            return
        }
        let wasCalibrating = clockAligner.state == nil || clockAligner.isCalibrating
        let timestampUs = clockAligner.align(rawTimestampUs: rawTimestampUs, nowUs: MediaClock.nowUs())
        if wasCalibrating { logClockState() }

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

    // A detected epoch mismatch is an error: the correction is then load-bearing, since
    // without it audio and video sit on different epochs.
    private func logClockState() {
        switch clockAligner.state {
        case .aligned(let latencyUs):
            Log.debug(.capture, "capture clock: shares media clock (latency \(latencyUs) us)")
        case .corrected(let offsetUs):
            Log.error(.capture, "capture clock: different epoch from media clock, correcting by \(offsetUs) us")
        case nil:
            break
        }
    }
}
