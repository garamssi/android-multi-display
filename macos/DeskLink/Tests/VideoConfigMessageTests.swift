import XCTest
@testable import DeskLink

final class VideoConfigMessageTests: XCTestCase {

    /// VIDEO_CONFIG golden vector (S-C3).
    /// codec=0x01 (HEVC), cfg=0000000140 (5 bytes)
    /// → 01 0005 0000000140
    func testSerializeGoldenVector() throws {
        let csd = Data(hex: "0000000140")
        let payload = try VideoConfigMessage.serialize(codec: .hevc, config: csd)
        XCTAssertEqual(payload.hexString, "0100050000000140")
    }

    func testCodecIDMapping() {
        XCTAssertEqual(VideoConfigMessage.CodecID(.hevc), .hevc)
        XCTAssertEqual(VideoConfigMessage.CodecID(.h264), .h264)
        XCTAssertEqual(VideoConfigMessage.CodecID.hevc.rawValue, 0x01)
        XCTAssertEqual(VideoConfigMessage.CodecID.h264.rawValue, 0x02)
    }

    func testConfigLengthEncoding() throws {
        let csd = Data(repeating: 0xAB, count: 300)
        let payload = try VideoConfigMessage.serialize(codec: .hevc, config: csd)
        // codec(1) + length(2) + 300 data
        XCTAssertEqual(payload.count, 3 + 300)
        XCTAssertEqual(payload[0], 0x01)
        // Length 300 = 0x012C big-endian.
        XCTAssertEqual(payload[1], 0x01)
        XCTAssertEqual(payload[2], 0x2C)
    }

    func testRejectsOversizedConfig() {
        let csd = Data(count: Int(UInt16.max) + 1)
        XCTAssertThrowsError(try VideoConfigMessage.serialize(codec: .hevc, config: csd)) { error in
            guard case VideoConfigMessage.BuildError.configTooLarge = error else {
                return XCTFail("Expected configTooLarge, got \(error)")
            }
        }
    }
}
