import XCTest
@testable import DeskLink

final class DisplayConfigTests: XCTestCase {

    func testDefaultConfigHasExpectedValues() {
        let config = DisplayConfig()
        XCTAssertEqual(config.width, 1920)
        XCTAssertEqual(config.height, 1200)
        XCTAssertEqual(config.fps, 60)
        XCTAssertEqual(config.codec, .hevc)
        XCTAssertEqual(config.bitrateKbps, 20_000)
        XCTAssertEqual(config.keyframeInterval, 2)
    }

    func testCodecRawValuesMatchProtocolSpec() {
        XCTAssertEqual(DisplayConfig.Codec.hevc.rawValue, 0x01)
        XCTAssertEqual(DisplayConfig.Codec.h264.rawValue, 0x02)
    }
}
