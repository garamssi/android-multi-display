import Foundation

/// Handles packet framing/unframing per protocol spec:
/// [4 bytes length (uint32 BE)] [1 byte type] [payload]
/// Length = size of (type + payload)
public enum PacketFramer {

    /// Maximum packet size (4MB)
    public static let maxPacketSize = ProtocolConstants.maxPacketSize

    /// Frames a message into a wire-format packet.
    /// - Parameters:
    ///   - type: Message type byte
    ///   - payload: Message payload data
    /// - Returns: Framed packet data ready for transmission
    public static func frame(type: MessageType, payload: Data) -> Data {
        let length = UInt32(1 + payload.count) // type byte + payload
        var packet = Data(capacity: 4 + Int(length))

        // Length (4 bytes, Big-Endian)
        var lengthBE = length.bigEndian
        packet.append(Data(bytes: &lengthBE, count: 4))

        // Type (1 byte)
        var typeByte = type.rawValue
        packet.append(Data(bytes: &typeByte, count: 1))

        // Payload
        packet.append(payload)

        return packet
    }

    /// Result of attempting to unframe a packet from a buffer.
    public enum UnframeResult {
        /// Successfully unframed a packet, with remaining buffer data.
        case success(type: MessageType, payload: Data, remaining: Data)
        /// Not enough data in buffer; need more bytes.
        case needMoreData
        /// Invalid packet (unknown type, oversized, etc.)
        case error(String)
    }

    /// Attempts to unframe one packet from the given buffer.
    /// - Parameter buffer: Raw received data
    /// - Returns: UnframeResult
    public static func unframe(buffer: Data) -> UnframeResult {
        // Need at least 5 bytes (4 length + 1 type)
        guard buffer.count >= 5 else { return .needMoreData }

        // Read length (Big-Endian uint32)
        let length = buffer.withUnsafeBytes { raw in
            raw.load(fromByteOffset: 0, as: UInt32.self).bigEndian
        }

        guard length >= 1 else { return .error("Invalid packet: length must be >= 1") }
        guard length <= maxPacketSize else { return .error("Packet too large: \(length) bytes") }

        let totalSize = 4 + Int(length) // header + body
        guard buffer.count >= totalSize else { return .needMoreData }

        // Read type
        let typeByte = buffer[4]
        guard let type = MessageType(rawValue: typeByte) else {
            return .error("Unknown message type: 0x\(String(typeByte, radix: 16))")
        }

        // Extract payload (skip 4 bytes length + 1 byte type)
        let payload = buffer.subdata(in: 5..<totalSize)
        let remaining = buffer.subdata(in: totalSize..<buffer.count)

        return .success(type: type, payload: payload, remaining: remaining)
    }
}
