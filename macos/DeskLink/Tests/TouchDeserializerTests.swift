import XCTest
@testable import DeskLink

final class TouchDeserializerTests: XCTestCase {

    func testDeserializeValidEvent() {
        // Construct a 20-byte touch event in Big-Endian:
        // Action(DOWN=0x00) + X(0.5 float32) + Y(0.75 float32) + Pressure(32768 uint16) + PointerID(2) + Timestamp(1234567890 int64)
        var data = Data()
        data.append(0x00) // DOWN

        var xBits = Float(0.5).bitPattern.bigEndian
        data.append(Data(bytes: &xBits, count: 4))

        var yBits = Float(0.75).bitPattern.bigEndian
        data.append(Data(bytes: &yBits, count: 4))

        var pressure = UInt16(32768).bigEndian
        data.append(Data(bytes: &pressure, count: 2))

        data.append(2) // pointer ID

        var timestamp = Int64(1234567890).bigEndian
        data.append(Data(bytes: &timestamp, count: 8))

        let event = TouchDeserializer.deserialize(data: data)
        XCTAssertNotNil(event)
        XCTAssertEqual(event?.action, .down)
        XCTAssertEqual(event?.x ?? 0, 0.5, accuracy: 0.0001)
        XCTAssertEqual(event?.y ?? 0, 0.75, accuracy: 0.0001)
        XCTAssertEqual(event?.pressure, 32768)
        XCTAssertEqual(event?.pointerId, 2)
        XCTAssertEqual(event?.timestampUs, 1234567890)
    }

    func testDeserializeReturnsNilForShortData() {
        let data = Data(repeating: 0, count: 10)
        XCTAssertNil(TouchDeserializer.deserialize(data: data))
    }

    func testDeserializeReturnsNilForOutOfRangeCoordinates() {
        var data = Data()
        data.append(0x00) // DOWN

        // X = 1.5 (out of range)
        var xBits = Float(1.5).bitPattern.bigEndian
        data.append(Data(bytes: &xBits, count: 4))

        var yBits = Float(0.5).bitPattern.bigEndian
        data.append(Data(bytes: &yBits, count: 4))

        var pressure = UInt16(0).bigEndian
        data.append(Data(bytes: &pressure, count: 2))
        data.append(0)

        var timestamp = Int64(0).bigEndian
        data.append(Data(bytes: &timestamp, count: 8))

        XCTAssertNil(TouchDeserializer.deserialize(data: data))
    }

    func testDeserializeBatchValidData() {
        let event1Data = createSerializedEvent(action: 0x00, x: 0.1, y: 0.2)
        let event2Data = createSerializedEvent(action: 0x02, x: 0.5, y: 0.5)

        var batchData = Data()
        var count = UInt16(2).bigEndian
        batchData.append(Data(bytes: &count, count: 2))
        batchData.append(event1Data)
        batchData.append(event2Data)

        let events = TouchDeserializer.deserializeBatch(data: batchData)
        XCTAssertEqual(events.count, 2)
        XCTAssertEqual(events[0].action, .down)
        XCTAssertEqual(events[1].action, .move)
    }

    // MARK: - Helpers

    private func createSerializedEvent(action: UInt8, x: Float, y: Float) -> Data {
        var data = Data()
        data.append(action)
        var xBits = x.bitPattern.bigEndian
        data.append(Data(bytes: &xBits, count: 4))
        var yBits = y.bitPattern.bigEndian
        data.append(Data(bytes: &yBits, count: 4))
        var pressure = UInt16(0).bigEndian
        data.append(Data(bytes: &pressure, count: 2))
        data.append(0) // pointer ID
        var timestamp = Int64(0).bigEndian
        data.append(Data(bytes: &timestamp, count: 8))
        return data
    }
}
