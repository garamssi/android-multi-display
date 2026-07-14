import Foundation

public enum PacketFramer {

    public static let maxPacketSize = ProtocolConstants.maxPacketSize

    public enum FramingError: Error, Equatable {
        case payloadTooLarge(Int)
    }

    public static func frame(type: MessageType, payload: Data) throws -> Data {
        let bodyLength = 1 + payload.count
        guard bodyLength <= maxPacketSize else {
            throw FramingError.payloadTooLarge(payload.count)
        }

        let length = UInt32(bodyLength)
        var packet = Data(capacity: 4 + bodyLength)

        withUnsafeBytes(of: length.bigEndian) { packet.append(contentsOf: $0) }

        packet.append(type.rawValue)

        packet.append(payload)

        return packet
    }

    public enum UnframeResult {
        case success(type: MessageType, payload: Data, remaining: Data)
        case needMoreData
        case error(String)
    }

    // Buffer may be a Data slice with a non-zero startIndex; index relative to startIndex so slices parse correctly.
    public static func unframe(buffer: Data) -> UnframeResult {
        guard buffer.count >= 5 else { return .needMoreData }

        let start = buffer.startIndex

        let length = buffer.withUnsafeBytes { raw in
            UInt32(bigEndian: raw.loadUnaligned(fromByteOffset: 0, as: UInt32.self))
        }

        guard length >= 1 else { return .error("Invalid packet: length must be >= 1") }
        guard UInt64(length) <= UInt64(maxPacketSize) else {
            return .error("Packet too large: \(length) bytes")
        }

        let totalSize = 4 + Int(length) // header + body
        guard buffer.count >= totalSize else { return .needMoreData }

        let typeByte = buffer[start + 4]
        guard let type = MessageType(rawValue: typeByte) else {
            return .error("Unknown message type: 0x\(String(typeByte, radix: 16))")
        }

        let payload = buffer.subdata(in: (start + 5)..<(start + totalSize))
        let remaining = buffer.subdata(in: (start + totalSize)..<buffer.endIndex)

        return .success(type: type, payload: payload, remaining: remaining)
    }
}
