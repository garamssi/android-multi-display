import Foundation

public protocol VideoEncoding: Sendable {
    func configure(config: DisplayConfig) async throws
    func encode(frame: VideoFrame) async throws -> EncodedFrame
    func updateBitrate(kbps: Int) async
    func forceKeyframe() async
}
