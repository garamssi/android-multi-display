import XCTest
@testable import DeskLink

/// Core Audio taps deliver 32-bit float samples; the wire format is signed 16-bit
/// little-endian PCM (see `AudioFormat`). This conversion is where clipping and
/// endianness go wrong silently, so it is pinned exactly.
final class PCMConverterTests: XCTestCase {

    private func convert(_ samples: [Float]) -> [UInt8] {
        var output = [UInt8](repeating: 0, count: samples.count * 2)
        samples.withUnsafeBufferPointer { input in
            output.withUnsafeMutableBytes { destination in
                PCMConverter.writeInt16LittleEndian(
                    from: input.baseAddress!,
                    sampleCount: input.count,
                    into: destination.baseAddress!
                )
            }
        }
        return output
    }

    func testSilenceConvertsToZero() {
        XCTAssertEqual(convert([0.0]), [0x00, 0x00])
    }

    /// Full scale maps to the positive maximum, and the bytes are little-endian:
    /// 32767 = 0x7FFF -> FF 7F.
    func testFullScalePositive() {
        XCTAssertEqual(convert([1.0]), [0xFF, 0x7F])
    }

    /// -1.0 maps to the negative minimum: -32768 = 0x8000 -> 00 80.
    func testFullScaleNegative() {
        XCTAssertEqual(convert([-1.0]), [0x00, 0x80])
    }

    /// Samples beyond [-1, 1] must CLAMP. Wrapping would turn a loud passage into
    /// harsh noise, because the sign bit flips at the peaks.
    func testOverdrivenSamplesClampInsteadOfWrapping() {
        XCTAssertEqual(convert([1.5]), [0xFF, 0x7F])
        XCTAssertEqual(convert([-1.5]), [0x00, 0x80])
        XCTAssertEqual(convert([42.0]), [0xFF, 0x7F])
        XCTAssertEqual(convert([-42.0]), [0x00, 0x80])
    }

    /// A NaN from a misbehaving source must become silence, not an arbitrary Int16 —
    /// converting NaN to an integer type traps in Swift.
    func testNonFiniteSamplesBecomeSilence() {
        XCTAssertEqual(convert([Float.nan]), [0x00, 0x00])
        XCTAssertEqual(convert([Float.infinity]), [0xFF, 0x7F])
        XCTAssertEqual(convert([-Float.infinity]), [0x00, 0x80])
    }

    func testHalfScaleRoundsToNearest() {
        // 0.5 * 32768 = 16384 = 0x4000 -> 00 40
        XCTAssertEqual(convert([0.5]), [0x00, 0x40])
    }

    func testInterleavedBlockKeepsSampleOrder() {
        XCTAssertEqual(
            convert([0.0, 1.0, -1.0]),
            [0x00, 0x00, 0xFF, 0x7F, 0x00, 0x80]
        )
    }

    func testEmptyInputWritesNothing() {
        XCTAssertEqual(convert([]), [])
    }

    /// Byte count per sample is what the caller sizes its slot with; a mismatch here
    /// silently truncates audio.
    func testBytesPerSample() {
        XCTAssertEqual(PCMConverter.bytesPerInt16Sample, 2)
    }
}
