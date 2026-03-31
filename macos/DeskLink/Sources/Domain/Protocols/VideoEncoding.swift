import Foundation

public protocol VideoEncoding: Sendable {
    func configure(config: DisplayConfig) async throws
    func encode(frame: VideoFrame) async throws -> EncodedFrame
    func updateBitrate(kbps: Int) async
    func forceKeyframe() async
}

public struct EncodedFrame: Sendable {
    public let data: Data
    public let timestampUs: Int64
    public let isKeyframe: Bool
    public let frameNumber: UInt32

    public init(data: Data, timestampUs: Int64, isKeyframe: Bool, frameNumber: UInt32) {
        self.data = data
        self.timestampUs = timestampUs
        self.isKeyframe = isKeyframe
        self.frameNumber = frameNumber
    }
}
