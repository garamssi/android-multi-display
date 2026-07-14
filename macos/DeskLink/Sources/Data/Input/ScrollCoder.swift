import Foundation

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
