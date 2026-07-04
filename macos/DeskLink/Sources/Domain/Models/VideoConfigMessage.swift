import Foundation

/// Builds the VIDEO_CONFIG (0x11) message payload per protocol spec:
/// `[CodecID u8 (1)][ConfigLength uint16 BE (2)][ConfigData...]`.
///
/// `ConfigData` is the codec-specific data (CSD): for HEVC the Annex-B
/// concatenation of VPS + SPS + PPS. This is sent once before the first
/// VIDEO_FRAME so the decoder can initialize.
public enum VideoConfigMessage {

    /// Codec identifiers used in the VIDEO_CONFIG payload.
    public enum CodecID: UInt8 {
        case hevc = 0x01
        case h264 = 0x02

        public init(_ codec: DisplayConfig.Codec) {
            switch codec {
            case .hevc: self = .hevc
            case .h264: self = .h264
            }
        }
    }

    public enum BuildError: Error, Equatable {
        /// CSD exceeds the 16-bit ConfigLength field (65 535 bytes).
        case configTooLarge(Int)
    }

    /// Serializes a VIDEO_CONFIG payload.
    /// - Parameters:
    ///   - codec: Codec identifier.
    ///   - config: CSD bytes (Annex-B VPS+SPS+PPS for HEVC).
    /// - Throws: `BuildError.configTooLarge` if `config` exceeds `UInt16.max` bytes.
    public static func serialize(codec: CodecID, config: Data) throws -> Data {
        guard config.count <= Int(UInt16.max) else {
            throw BuildError.configTooLarge(config.count)
        }

        var payload = Data(capacity: 3 + config.count)
        payload.append(codec.rawValue)
        withUnsafeBytes(of: UInt16(config.count).bigEndian) { payload.append(contentsOf: $0) }
        payload.append(config)
        return payload
    }
}
