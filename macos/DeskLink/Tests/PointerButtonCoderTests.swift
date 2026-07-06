import XCTest
@testable import DeskLink

/// POINTER_BUTTON (0x23) wire format.
final class PointerButtonCoderTests: XCTestCase {

    /// Matches the authoritative golden vector in tools/protocol_vectors.py
    /// (RIGHT down at x=0.5, y=0.25 -> 01003F0000003E800000).
    func testSerializeMatchesGoldenVector() {
        let event = PointerButtonEvent(button: .right, action: .down, x: 0.5, y: 0.25)
        let data = PointerButtonSerializer.serialize(event)
        XCTAssertEqual(data.hexString, "01003F0000003E800000")
        XCTAssertEqual(data.count, PointerButtonEvent.serializedSize)
    }

    func testRoundTrip() {
        let original = PointerButtonEvent(button: .left, action: .up, x: 0.123, y: 0.987)
        let decoded = PointerButtonDeserializer.deserialize(data: PointerButtonSerializer.serialize(original))
        XCTAssertEqual(decoded, original)
    }

    func testDeserializeRejectsShortPayload() {
        XCTAssertNil(PointerButtonDeserializer.deserialize(data: Data(count: 9)))
    }

    func testDeserializeRejectsUnknownButton() {
        // Button 0x02 is not defined; must not decode.
        var data = Data([0x02, 0x00])
        data.append(Data(count: 8))
        XCTAssertNil(PointerButtonDeserializer.deserialize(data: data))
    }

    func testDeserializeRejectsOutOfRangeCoordinate() {
        let event = PointerButtonEvent(button: .right, action: .down, x: 1.5, y: 0.5)
        XCTAssertNil(PointerButtonDeserializer.deserialize(data: PointerButtonSerializer.serialize(event)))
    }
}
