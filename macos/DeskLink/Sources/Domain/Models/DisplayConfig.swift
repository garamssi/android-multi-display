import Foundation

public struct DisplayConfig: Sendable, Equatable {
    public var width: Int
    public var height: Int
    public var fps: Int
    public var codec: Codec
    public var bitrateKbps: Int
    public var keyframeInterval: Int

    public init(
        width: Int = 1920,
        height: Int = 1200,
        fps: Int = 60,
        codec: Codec = .hevc,
        bitrateKbps: Int = 20_000,
        keyframeInterval: Int = 2
    ) {
        self.width = width
        self.height = height
        self.fps = fps
        self.codec = codec
        self.bitrateKbps = bitrateKbps
        self.keyframeInterval = keyframeInterval
    }

    public enum Codec: UInt8, Sendable {
        case hevc = 0x01
        case h264 = 0x02
    }
}
