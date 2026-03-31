import Foundation

public struct VideoFrame: Sendable {
    public let data: Data
    public let timestampUs: Int64
    public let isKeyframe: Bool

    public init(data: Data, timestampUs: Int64, isKeyframe: Bool) {
        self.data = data
        self.timestampUs = timestampUs
        self.isKeyframe = isKeyframe
    }
}
