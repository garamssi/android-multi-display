import XCTest
@testable import DeskLink

final class AnnexBConverterTests: XCTestCase {

    /// Single AVCC NAL: [00 00 00 03][26 00 42] → [00 00 00 01][26 00 42]
    func testSingleNALU() throws {
        let avcc = Data(hex: "00000003" + "260042")
        let annexB = try AnnexBConverter.convert(avcc: avcc)
        XCTAssertEqual(annexB.hexString, "00000001" + "260042")
    }

    /// Multiple AVCC NAL units get individual start codes.
    /// [00 00 00 02][26 00][00 00 00 03][40 01 0C]
    /// → [00 00 00 01][26 00][00 00 00 01][40 01 0C]
    func testMultipleNALUs() throws {
        let avcc = Data(hex: "00000002" + "2600" + "00000003" + "40010C")
        let annexB = try AnnexBConverter.convert(avcc: avcc)
        XCTAssertEqual(annexB.hexString, "00000001" + "2600" + "00000001" + "40010C")
    }

    /// Length prefix running past the end of the buffer is rejected.
    func testMalformedTruncatedNAL() {
        // Length says 10 bytes but only 2 present.
        let avcc = Data(hex: "0000000A" + "2600")
        XCTAssertThrowsError(try AnnexBConverter.convert(avcc: avcc)) { error in
            XCTAssertEqual(error as? AnnexBConverter.ConversionError, .malformedAVCC)
        }
    }

    /// A truncated length prefix (fewer than lengthSize bytes remaining) is rejected.
    func testMalformedTruncatedLengthPrefix() {
        let avcc = Data(hex: "000001") // only 3 bytes, lengthSize 4
        XCTAssertThrowsError(try AnnexBConverter.convert(avcc: avcc)) { error in
            XCTAssertEqual(error as? AnnexBConverter.ConversionError, .malformedAVCC)
        }
    }

    func testEmptyInputProducesEmptyOutput() throws {
        let annexB = try AnnexBConverter.convert(avcc: Data())
        XCTAssertTrue(annexB.isEmpty)
    }

    func testInvalidLengthSizeRejected() {
        XCTAssertThrowsError(try AnnexBConverter.convert(avcc: Data(), lengthSize: 3)) { error in
            XCTAssertEqual(error as? AnnexBConverter.ConversionError, .invalidLengthSize(3))
        }
    }

    /// 2-byte length prefixes are supported.
    func testTwoByteLengthPrefix() throws {
        let avcc = Data(hex: "0003" + "260042")
        let annexB = try AnnexBConverter.convert(avcc: avcc, lengthSize: 2)
        XCTAssertEqual(annexB.hexString, "00000001" + "260042")
    }
}
