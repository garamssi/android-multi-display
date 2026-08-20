import Foundation

// Wire fields sit at non-8-byte-aligned offsets; use loadUnaligned, not load (misaligned load is UB).
public enum TouchDeserializer {

    public static func deserialize(data: Data) -> TouchEvent? {
        guard data.count >= TouchEvent.serializedSize else { return nil }

        return data.withUnsafeBytes { raw in
            parseEvent(raw, at: 0)
        }
    }

    public static func deserializeBatch(data: Data) -> [TouchEvent] {
        guard data.count >= 2 else { return [] }

        return data.withUnsafeBytes { raw -> [TouchEvent] in
            let count = Int(UInt16(bigEndian: raw.loadUnaligned(fromByteOffset: 0, as: UInt16.self)))

            guard count > 0, count <= 100 else { return [] }

            let expectedSize = 2 + count * TouchEvent.serializedSize
            guard raw.count >= expectedSize else { return [] }

            var events: [TouchEvent] = []
            events.reserveCapacity(count)

            for i in 0..<count {
                let offset = 2 + i * TouchEvent.serializedSize
                if let event = parseEvent(raw, at: offset) {
                    events.append(event)
                }
            }

            return events
        }
    }

    // MARK: - Private

    private static func parseEvent(_ raw: UnsafeRawBufferPointer, at base: Int) -> TouchEvent? {
        guard base + TouchEvent.serializedSize <= raw.count else { return nil }
        var offset = base

        guard let action = TouchEvent.Action(
            rawValue: raw.loadUnaligned(fromByteOffset: offset, as: UInt8.self)
        ) else {
            return nil
        }
        offset += 1

        let x = Float(bitPattern: UInt32(bigEndian: raw.loadUnaligned(fromByteOffset: offset, as: UInt32.self)))
        offset += 4

        let y = Float(bitPattern: UInt32(bigEndian: raw.loadUnaligned(fromByteOffset: offset, as: UInt32.self)))
        offset += 4

        let pressure = UInt16(bigEndian: raw.loadUnaligned(fromByteOffset: offset, as: UInt16.self))
        offset += 2

        let pointerId = raw.loadUnaligned(fromByteOffset: offset, as: UInt8.self)
        offset += 1

        let timestampUs = Int64(bigEndian: raw.loadUnaligned(fromByteOffset: offset, as: Int64.self))

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

public enum TouchSerializer {

    public static func serialize(_ event: TouchEvent) -> Data {
        var data = Data(capacity: TouchEvent.serializedSize)

        data.append(event.action.rawValue)
        appendBE(&data, event.x.bitPattern)        // X  float32 -> uint32 BE
        appendBE(&data, event.y.bitPattern)        // Y  float32 -> uint32 BE
        appendBE(&data, event.pressure)            // Pressure uint16 BE
        data.append(event.pointerId)               // PointerID uint8
        appendBE(&data, UInt64(bitPattern: event.timestampUs)) // Timestamp int64 BE

        return data
    }

    public static func serializeBatch(_ events: [TouchEvent]) -> Data {
        precondition(events.count <= 100, "TOUCH_BATCH max count is 100")
        var data = Data(capacity: 2 + events.count * TouchEvent.serializedSize)
        appendBE(&data, UInt16(events.count))
        for event in events {
            data.append(serialize(event))
        }
        return data
    }

    // MARK: - Private

    private static func appendBE(_ data: inout Data, _ value: UInt16) {
        withUnsafeBytes(of: value.bigEndian) { data.append(contentsOf: $0) }
    }

    private static func appendBE(_ data: inout Data, _ value: UInt32) {
        withUnsafeBytes(of: value.bigEndian) { data.append(contentsOf: $0) }
    }

    private static func appendBE(_ data: inout Data, _ value: UInt64) {
        withUnsafeBytes(of: value.bigEndian) { data.append(contentsOf: $0) }
    }
}
