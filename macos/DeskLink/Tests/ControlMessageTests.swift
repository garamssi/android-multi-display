import XCTest
@testable import DeskLink

final class ControlMessageTests: XCTestCase {

    /// PING golden vector: int64 ms ts=1700000000000 → 0000018BCFE56800
    func testPingPayloadGoldenVector() {
        let payload = ControlMessage.timestampPayload(millis: 1_700_000_000_000)
        XCTAssertEqual(payload.hexString, "0000018BCFE56800")
        XCTAssertEqual(payload.count, 8)
    }

    func testTimestampRoundTrip() {
        let ts: Int64 = 1_700_000_000_000
        let payload = ControlMessage.timestampPayload(millis: ts)
        XCTAssertEqual(ControlMessage.parseTimestamp(payload), ts)
    }

    func testParseRejectsWrongSize() {
        XCTAssertNil(ControlMessage.parseTimestamp(Data(count: 7)))
        XCTAssertNil(ControlMessage.parseTimestamp(Data(count: 9)))
    }
}
