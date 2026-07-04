import XCTest
@testable import DeskLink

final class EncodedFrameTests: XCTestCase {

    /// VIDEO_FRAME golden vector (S-C2).
    /// ts=1000000us, flags=0x01 (keyframe), frameNo=42, nal=000000012600
    /// → 00000000000F4240 01 0000002A 000000012600
    func testSerializeGoldenVector() {
        let nal = Data(hex: "000000012600")
        let frame = EncodedFrame(
            data: nal,
            timestampUs: 1_000_000,
            isKeyframe: true,
            frameNumber: 42
        )
        let payload = frame.serialize()
        XCTAssertEqual(payload.hexString, "00000000000F4240010000002A000000012600")
        XCTAssertEqual(payload.count, EncodedFrame.headerSize + nal.count)
    }

    func testSerializeNonKeyframeClearsFlagBit0() {
        let nal = Data(hex: "000000012600")
        let frame = EncodedFrame(
            data: nal,
            timestampUs: 1_000_000,
            isKeyframe: false,
            frameNumber: 42
        )
        let payload = frame.serialize()
        // Flags byte is at offset 8 and must be 0x00 for a non-keyframe.
        XCTAssertEqual(payload[8], 0x00)
    }

    func testFlagBitConstants() {
        XCTAssertEqual(EncodedFrame.Flags.isKeyframe, 0x01)
        XCTAssertEqual(EncodedFrame.Flags.isConfig, 0x02)
    }

    func testHeaderSizeIs13() {
        XCTAssertEqual(EncodedFrame.headerSize, 13)
    }

    /// Negative timestamps round-trip through the int64 field.
    func testSerializeHandlesNegativeTimestamp() {
        let frame = EncodedFrame(data: Data([0xAA]), timestampUs: -1, isKeyframe: false, frameNumber: 0)
        let payload = frame.serialize()
        // int64 -1 big-endian = FFFFFFFFFFFFFFFF, flags 00, frameNo 00000000, nal AA
        XCTAssertEqual(payload.hexString, "FFFFFFFFFFFFFFFF0000000000AA")
    }
}
