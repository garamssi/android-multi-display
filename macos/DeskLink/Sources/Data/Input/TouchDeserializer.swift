import Foundation

/// Deserializes touch events from wire format per protocol spec.
/// Format: Action(1) + X(4 float32) + Y(4 float32) + Pressure(2 uint16) + PointerID(1) + Timestamp(8 int64)
/// Total: 20 bytes
public enum TouchDeserializer {

    public static func deserialize(data: Data) -> TouchEvent? {
        guard data.count >= TouchEvent.serializedSize else { return nil }

        return data.withUnsafeBytes { raw in
            var offset = 0

            guard let action = TouchEvent.Action(rawValue: raw.load(fromByteOffset: offset, as: UInt8.self)) else {
                return nil
            }
            offset += 1

            let x = Float(bitPattern: UInt32(bigEndian: raw.load(fromByteOffset: offset, as: UInt32.self)))
            offset += 4

            let y = Float(bitPattern: UInt32(bigEndian: raw.load(fromByteOffset: offset, as: UInt32.self)))
            offset += 4

            let pressure = UInt16(bigEndian: raw.load(fromByteOffset: offset, as: UInt16.self))
            offset += 2

            let pointerId = raw.load(fromByteOffset: offset, as: UInt8.self)
            offset += 1

            let timestampUs = Int64(bigEndian: raw.load(fromByteOffset: offset, as: Int64.self))

            guard x >= 0, x <= 1, y >= 0, y <= 1 else { return nil }

            return TouchEvent(
                action: action,
                x: x,
                y: y,
                pressure: pressure,
                pointerId: pointerId,
                timestampUs: timestampUs
            )
        }
    }

    public static func deserializeBatch(data: Data) -> [TouchEvent] {
        guard data.count >= 2 else { return [] }

        let count = data.withUnsafeBytes { raw in
            Int(UInt16(bigEndian: raw.load(fromByteOffset: 0, as: UInt16.self)))
        }

        guard count > 0, count <= 100 else { return [] }

        let expectedSize = 2 + count * TouchEvent.serializedSize
        guard data.count >= expectedSize else { return [] }

        var events: [TouchEvent] = []
        events.reserveCapacity(count)

        for i in 0..<count {
            let offset = 2 + i * TouchEvent.serializedSize
            let eventData = data.subdata(in: offset..<(offset + TouchEvent.serializedSize))
            if let event = deserialize(data: eventData) {
                events.append(event)
            }
        }

        return events
    }
}
