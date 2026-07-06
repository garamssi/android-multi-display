import Foundation

/// Deserializes SCROLL (0x22) payloads per protocol spec:
/// DeltaX(f32) + DeltaY(f32) = 8 bytes, Big-Endian.
public enum ScrollDeserializer {

    public static func deserialize(data: Data) -> ScrollEvent? {
        guard data.count >= ScrollEvent.serializedSize else { return nil }
        return data.withUnsafeBytes { raw in
            let dx = Float(bitPattern: UInt32(bigEndian: raw.loadUnaligned(fromByteOffset: 0, as: UInt32.self)))
            let dy = Float(bitPattern: UInt32(bigEndian: raw.loadUnaligned(fromByteOffset: 4, as: UInt32.self)))
            return ScrollEvent(deltaX: dx, deltaY: dy)
        }
    }
}

/// Mirror serializer for round-trip testing against the protocol golden vectors and
/// to document the exact byte layout (the Mac only receives SCROLL; Android sends it).
public enum ScrollSerializer {

    public static func serialize(_ scroll: ScrollEvent) -> Data {
        var data = Data(capacity: ScrollEvent.serializedSize)
        appendBE(&data, scroll.deltaX.bitPattern) // DeltaX float32 -> uint32 BE
        appendBE(&data, scroll.deltaY.bitPattern) // DeltaY float32 -> uint32 BE
        return data
    }

    private static func appendBE(_ data: inout Data, _ value: UInt32) {
        withUnsafeBytes(of: value.bigEndian) { data.append(contentsOf: $0) }
    }
}
