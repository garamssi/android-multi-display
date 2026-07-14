import Foundation

public enum PointerButtonDeserializer {

    public static func deserialize(data: Data) -> PointerButtonEvent? {
        guard data.count >= PointerButtonEvent.serializedSize else { return nil }
        return data.withUnsafeBytes { raw -> PointerButtonEvent? in
            guard let button = PointerButtonEvent.Button(
                rawValue: raw.loadUnaligned(fromByteOffset: 0, as: UInt8.self)
            ) else { return nil }
            guard let action = PointerButtonEvent.Action(
                rawValue: raw.loadUnaligned(fromByteOffset: 1, as: UInt8.self)
            ) else { return nil }

            let x = Float(bitPattern: UInt32(bigEndian: raw.loadUnaligned(fromByteOffset: 2, as: UInt32.self)))
            let y = Float(bitPattern: UInt32(bigEndian: raw.loadUnaligned(fromByteOffset: 6, as: UInt32.self)))
            guard x >= 0, x <= 1, y >= 0, y <= 1 else { return nil }

            return PointerButtonEvent(button: button, action: action, x: x, y: y)
        }
    }
}

public enum PointerButtonSerializer {

    public static func serialize(_ event: PointerButtonEvent) -> Data {
        var data = Data(capacity: PointerButtonEvent.serializedSize)
        data.append(event.button.rawValue)
        data.append(event.action.rawValue)
        appendBE(&data, event.x.bitPattern) // X float32 -> uint32 BE
        appendBE(&data, event.y.bitPattern) // Y float32 -> uint32 BE
        return data
    }

    private static func appendBE(_ data: inout Data, _ value: UInt32) {
        withUnsafeBytes(of: value.bigEndian) { data.append(contentsOf: $0) }
    }
}
