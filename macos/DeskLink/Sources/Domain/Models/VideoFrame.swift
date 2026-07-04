import Foundation

public struct VideoFrame: Sendable {
    public let data: Data
    public let timestampUs: Int64
    public let isKeyframe: Bool
    /// Pixel dimensions of the captured frame (0 if unknown).
    public let width: Int
    public let height: Int
    /// Bytes per row of `data` (source stride, may include padding; 0 if unknown).
    public let bytesPerRow: Int

    public init(
        data: Data,
        timestampUs: Int64,
        isKeyframe: Bool,
        width: Int = 0,
        height: Int = 0,
        bytesPerRow: Int = 0
    ) {
        self.data = data
        self.timestampUs = timestampUs
        self.isKeyframe = isKeyframe
        self.width = width
        self.height = height
        self.bytesPerRow = bytesPerRow
    }
}
