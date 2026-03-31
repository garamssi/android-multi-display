import Foundation
@preconcurrency import CoreGraphics
import IOSurface
import CoreVideo

public final class ScreenCapturer: ScreenCapturing, @unchecked Sendable {
    private var displayStream: CGDisplayStream?
    private let lock = NSLock()

    public init() {}

    @available(macOS, deprecated: 14.0, message: "Consider migrating to ScreenCaptureKit for macOS 14+")
    public func startCapture(displayID: UInt32, fps: Int) -> AsyncThrowingStream<VideoFrame, Error> {
        AsyncThrowingStream<VideoFrame, Error>(bufferingPolicy: .bufferingNewest(3)) { continuation in
            let frameDuration = 1.0 / Double(fps)

            lock.lock()
            let displayBounds = CGDisplayBounds(displayID)
            let width = Int(displayBounds.width)
            let height = Int(displayBounds.height)

            guard width > 0, height > 0 else {
                lock.unlock()
                continuation.finish(throwing: ConnectionError.displayCaptureFailed)
                return
            }

            let properties: CFDictionary = [
                CGDisplayStream.minimumFrameTime: frameDuration,
                CGDisplayStream.showCursor: true,
            ] as CFDictionary

            let stream = CGDisplayStream(
                dispatchQueueDisplay: displayID,
                outputWidth: width,
                outputHeight: height,
                pixelFormat: Int32(kCVPixelFormatType_32BGRA),
                properties: properties,
                queue: DispatchQueue(label: "com.desklink.capture", qos: .userInteractive),
                handler: { status, displayTime, frameSurface, _ in
                    guard status == .frameComplete else { return }
                    guard let surface = frameSurface else { return }

                    let timestampUs = Int64(displayTime * 1_000_000)

                    IOSurfaceLock(surface, .readOnly, nil)
                    let baseAddress = IOSurfaceGetBaseAddress(surface)
                    let bytesPerRow = IOSurfaceGetBytesPerRow(surface)
                    let height = IOSurfaceGetHeight(surface)
                    let totalBytes = bytesPerRow * height
                    let data = Data(bytes: baseAddress, count: totalBytes)
                    IOSurfaceUnlock(surface, .readOnly, nil)

                    let frame = VideoFrame(
                        data: data,
                        timestampUs: timestampUs,
                        isKeyframe: false
                    )
                    continuation.yield(frame)
                }
            )

            guard let stream = stream else {
                lock.unlock()
                continuation.finish(throwing: ConnectionError.displayCaptureFailed)
                return
            }

            let startError = stream.start()
            if startError != .success {
                lock.unlock()
                continuation.finish(throwing: ConnectionError.displayCaptureFailed)
                return
            }

            self.displayStream = stream
            lock.unlock()

            continuation.onTermination = { @Sendable _ in
                stream.stop()
            }
        }
    }

    public func stopCapture() async {
        lock.withLock {
            displayStream?.stop()
            displayStream = nil
        }
    }
}
