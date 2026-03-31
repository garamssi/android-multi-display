import Foundation

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
