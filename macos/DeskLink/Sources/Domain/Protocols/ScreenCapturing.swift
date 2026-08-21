import Foundation

public protocol ScreenCapturing: Sendable {
    func startCapture(source: StreamSource, fps: Int) -> AsyncThrowingStream<VideoFrame, Error>
    func stopCapture() async
}
