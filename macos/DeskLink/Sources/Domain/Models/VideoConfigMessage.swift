import Foundation

public enum VideoConfigMessage {

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
        case configTooLarge(Int)
    }

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
