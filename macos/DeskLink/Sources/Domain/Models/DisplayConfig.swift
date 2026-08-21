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

    // Same config at a different capture size. Used when the source cannot deliver the
    // requested resolution — a mirrored display has its own fixed size, and a virtual
    // display can fall back to another mode — so the encoder is configured for the pixels
    // that actually arrive rather than the ones that were asked for.
    public func withResolution(width: Int, height: Int) -> DisplayConfig {
        var copy = self
        copy.width = width
        copy.height = height
        return copy
    }
}
