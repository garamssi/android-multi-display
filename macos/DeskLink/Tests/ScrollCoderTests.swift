import XCTest
import CoreGraphics
@testable import DeskLink

/// SCROLL (0x22) wire format + scroll-to-pixel mapping.
final class ScrollCoderTests: XCTestCase {

    /// Matches the authoritative golden vector in tools/protocol_vectors.py
    /// (SCROLL dx=0.25, dy=-0.5 -> 3E800000BF000000).
    func testSerializeMatchesGoldenVector() {
        let data = ScrollSerializer.serialize(ScrollEvent(deltaX: 0.25, deltaY: -0.5))
        XCTAssertEqual(data.hexString, "3E800000BF000000")
        XCTAssertEqual(data.count, ScrollEvent.serializedSize)
    }

    func testRoundTrip() {
        let original = ScrollEvent(deltaX: 0.123, deltaY: -0.987)
        let decoded = ScrollDeserializer.deserialize(data: ScrollSerializer.serialize(original))
        XCTAssertEqual(decoded, original)
    }

    func testDeserializeRejectsShortPayload() {
        XCTAssertNil(ScrollDeserializer.deserialize(data: Data(count: 4)))
    }

    // MARK: - scroll -> pixel mapping (scaled by point size, 1:1, unrounded)
    // The tablet applies the user's scroll sensitivity before sending, so the Mac
    // maps the incoming delta to pixels 1:1 (no server-side gain).

    func testScrollScalesByPointSize() {
        let bounds = CGRect(x: 0, y: 0, width: 1600, height: 1000)
        // deltaY 0.1 of a 1000pt display = 100px, natural sign (-1) -> -100.
        let (v, h) = CGEventInjector.scaledScrollPixels(deltaX: 0, deltaY: 0.1, displayBounds: bounds)
        XCTAssertEqual(v, -100, accuracy: 0.001)
        XCTAssertEqual(h, 0, accuracy: 0.001)
    }

    func testHorizontalScroll() {
        let bounds = CGRect(x: 0, y: 0, width: 1600, height: 1000)
        // deltaX 0.25 of 1600pt = 400px, natural sign (-1) -> -400.
        let (v, h) = CGEventInjector.scaledScrollPixels(deltaX: 0.25, deltaY: 0, displayBounds: bounds)
        XCTAssertEqual(v, 0, accuracy: 0.001)
        XCTAssertEqual(h, -400, accuracy: 0.001)
    }

    func testSubPixelDeltaIsNotPrematurelyZeroed() {
        let bounds = CGRect(x: 0, y: 0, width: 1000, height: 1000)
        // A tiny delta stays a small nonzero value (rounding/carry is done in
        // injectScroll, not here) so slow scrolling isn't lost.
        let (v, _) = CGEventInjector.scaledScrollPixels(deltaX: 0, deltaY: 0.0001, displayBounds: bounds)
        XCTAssertEqual(v, -0.1, accuracy: 0.0001) // -1 * 0.0001 * 1000
    }
}
