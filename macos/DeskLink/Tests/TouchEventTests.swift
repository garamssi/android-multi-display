import XCTest
@testable import DeskLink

final class TouchEventTests: XCTestCase {

    func testSerializedSizeMatchesProtocolSpec() {
        XCTAssertEqual(TouchEvent.serializedSize, 20)
    }

    func testActionRawValuesMatchProtocolSpec() {
        XCTAssertEqual(TouchEvent.Action.down.rawValue, 0x00)
        XCTAssertEqual(TouchEvent.Action.up.rawValue, 0x01)
        XCTAssertEqual(TouchEvent.Action.move.rawValue, 0x02)
        XCTAssertEqual(TouchEvent.Action.cancel.rawValue, 0x03)
    }

    func testValidTouchEventCreation() {
        let event = TouchEvent(
            action: .down,
            x: 0.5,
            y: 0.75,
            pressure: 32768,
            pointerId: 0,
            timestampUs: 1000
        )
        XCTAssertEqual(event.x, 0.5)
        XCTAssertEqual(event.y, 0.75)
    }
}
