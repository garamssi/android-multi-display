import Foundation

public protocol ScreenCapturing: Sendable {
    func startCapture(displayID: UInt32, fps: Int) -> AsyncThrowingStream<VideoFrame, Error>
    func stopCapture() async
}
