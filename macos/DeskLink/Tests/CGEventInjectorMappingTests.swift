import XCTest
import CoreGraphics
@testable import DeskLink

/// Verifies the normalized-coordinate -> global-point mapping used for input
/// injection. CGEvent cursor positions are in the GLOBAL display coordinate space,
/// which is measured in POINTS — so normalized [0,1] must map onto the display's
/// on-screen rectangle (`CGDisplayBounds`), not its pixel resolution. Mapping against
/// pixels breaks on HiDPI/Retina (cursor lands off the display).
final class CGEventInjectorMappingTests: XCTestCase {

    func testCornersAndCenterMapToBounds() {
        let bounds = CGRect(x: 0, y: 0, width: 1600, height: 1000)
        XCTAssertEqual(
            CGEventInjector.globalPoint(normalizedX: 0, normalizedY: 0, displayBounds: bounds),
            CGPoint(x: 0, y: 0)
        )
        XCTAssertEqual(
            CGEventInjector.globalPoint(normalizedX: 1, normalizedY: 1, displayBounds: bounds),
            CGPoint(x: 1600, y: 1000)
        )
        XCTAssertEqual(
            CGEventInjector.globalPoint(normalizedX: 0.5, normalizedY: 0.5, displayBounds: bounds),
            CGPoint(x: 800, y: 500)
        )
    }

    func testAppliesDisplayOriginOffset() {
        // A secondary display placed to the right and above the main one.
        let bounds = CGRect(x: 1512, y: -300, width: 1512, height: 982)
        let point = CGEventInjector.globalPoint(normalizedX: 0.5, normalizedY: 0.5, displayBounds: bounds)
        XCTAssertEqual(point.x, 1512 + 756, accuracy: 0.001)
        XCTAssertEqual(point.y, -300 + 491, accuracy: 0.001)
    }

    func testMapsToPointSizeNotPixelResolution() {
        // A 3200x2000-pixel panel shown at 1600x1000 POINTS (2x scale): the bottom-right
        // corner must land at the point size, not the pixel resolution.
        let pointBounds = CGRect(x: 0, y: 0, width: 1600, height: 1000)
        XCTAssertEqual(
            CGEventInjector.globalPoint(normalizedX: 1, normalizedY: 1, displayBounds: pointBounds),
            CGPoint(x: 1600, y: 1000)
        )
    }

    func testClampsOutOfRangeInput() {
        let bounds = CGRect(x: 0, y: 0, width: 100, height: 100)
        XCTAssertEqual(
            CGEventInjector.globalPoint(normalizedX: -0.5, normalizedY: 2.0, displayBounds: bounds),
            CGPoint(x: 0, y: 100)
        )
    }
}
