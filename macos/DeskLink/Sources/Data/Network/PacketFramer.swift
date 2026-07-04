import Foundation

/// Handles packet framing/unframing per protocol spec:
/// [4 bytes length (uint32 BE)] [1 byte type] [payload]
/// Length = size of (type + payload). Max packet size is enforced on both
/// framing (send) and unframing (receive). Size arithmetic is performed in
/// `Int`/`UInt64` and validated before any narrowing to `UInt32`, so a payload
/// that would overflow the 32-bit length field is rejected rather than wrapped.
public enum PacketFramer {

    /// Maximum packet size (4MB). Applies to the framed body (type + payload).
    public static let maxPacketSize = ProtocolConstants.maxPacketSize

    public enum FramingError: Error, Equatable {
        /// Payload is too large to fit within `maxPacketSize`.
        case payloadTooLarge(Int)
    }

    /// Frames a message into a wire-format packet.
    /// - Parameters:
    ///   - type: Message type byte
    ///   - payload: Message payload data
    /// - Returns: Framed packet data ready for transmission
    /// - Throws: `FramingError.payloadTooLarge` if the body would exceed `maxPacketSize`.
    public static func frame(type: MessageType, payload: Data) throws -> Data {
        // Do size math in Int to avoid UInt32 overflow. Body = type byte + payload.
        let bodyLength = 1 + payload.count
        guard bodyLength <= maxPacketSize else {
            throw FramingError.payloadTooLarge(payload.count)
        }

        let length = UInt32(bodyLength)
        var packet = Data(capacity: 4 + bodyLength)

        // Length (4 bytes, Big-Endian)
        withUnsafeBytes(of: length.bigEndian) { packet.append(contentsOf: $0) }

        // Type (1 byte)
        packet.append(type.rawValue)

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
    ///
    /// The buffer may be a `Data` slice with a non-zero `startIndex`; all
    /// indexing is done relative to `startIndex` so slices parse correctly.
    /// - Parameter buffer: Raw received data
    /// - Returns: UnframeResult
    public static func unframe(buffer: Data) -> UnframeResult {
        // Need at least 5 bytes (4 length + 1 type)
        guard buffer.count >= 5 else { return .needMoreData }

        let start = buffer.startIndex

        // Read length (Big-Endian uint32). loadUnaligned is safe for the
        // possibly-misaligned buffer start; offset 0 relative to startIndex.
        let length = buffer.withUnsafeBytes { raw in
            UInt32(bigEndian: raw.loadUnaligned(fromByteOffset: 0, as: UInt32.self))
        }

        guard length >= 1 else { return .error("Invalid packet: length must be >= 1") }
        // Compare in UInt64 to avoid any narrowing surprises.
        guard UInt64(length) <= UInt64(maxPacketSize) else {
            return .error("Packet too large: \(length) bytes")
        }

        let totalSize = 4 + Int(length) // header + body
        guard buffer.count >= totalSize else { return .needMoreData }

        // Read type (offset 4 relative to the buffer's start index).
        let typeByte = buffer[start + 4]
        guard let type = MessageType(rawValue: typeByte) else {
            return .error("Unknown message type: 0x\(String(typeByte, radix: 16))")
        }

        // Extract payload (skip 4 bytes length + 1 byte type) and remaining bytes.
        // subdata re-bases the returned Data to a 0 start index.
        let payload = buffer.subdata(in: (start + 5)..<(start + totalSize))
        let remaining = buffer.subdata(in: (start + totalSize)..<buffer.endIndex)

        return .success(type: type, payload: payload, remaining: remaining)
    }
}
