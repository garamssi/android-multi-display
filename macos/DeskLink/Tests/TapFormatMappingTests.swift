import CoreAudio
import XCTest
@testable import DeskLink

/// The IO proc reinterprets the tap's buffer as interleaved 32-bit floats. If the tap
/// ever hands us a different layout, that reinterpretation produces noise rather than
/// audio — so the layout is validated up front and rejected loudly.
@available(macOS 14.2, *)
final class TapFormatMappingTests: XCTestCase {

    private func asbd(
        sampleRate: Float64 = 48_000,
        channels: UInt32 = 2,
        bitsPerChannel: UInt32 = 32,
        formatID: AudioFormatID = kAudioFormatLinearPCM,
        flags: AudioFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked
    ) -> AudioStreamBasicDescription {
        var description = AudioStreamBasicDescription()
        description.mSampleRate = sampleRate
        description.mFormatID = formatID
        description.mFormatFlags = flags
        description.mBitsPerChannel = bitsPerChannel
        description.mChannelsPerFrame = channels
        return description
    }

    func testInterleavedFloatStereoMapsToWireFormat() throws {
        let format = try CoreAudioTapCapturer.wireFormat(from: asbd())
        XCTAssertEqual(format.sampleRate, 48_000)
        XCTAssertEqual(format.channelCount, 2)
        XCTAssertEqual(format.bitsPerSample, 16)
        XCTAssertEqual(format.encoding, .pcmSignedLittleEndian16)
    }

    func testMonoTapIsAccepted() throws {
        XCTAssertEqual(try CoreAudioTapCapturer.wireFormat(from: asbd(channels: 1)).channelCount, 1)
    }

    func testNonStandardSampleRateIsPreserved() throws {
        XCTAssertEqual(try CoreAudioTapCapturer.wireFormat(from: asbd(sampleRate: 44_100)).sampleRate, 44_100)
    }

    /// Non-interleaved gives one buffer PER CHANNEL; reading only the first buffer would
    /// silently drop every channel but the left and halve the apparent frame count.
    func testNonInterleavedIsRejected() {
        XCTAssertThrowsError(
            try CoreAudioTapCapturer.wireFormat(
                from: asbd(flags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsNonInterleaved)
            )
        )
    }

    func testIntegerPCMIsRejected() {
        XCTAssertThrowsError(
            try CoreAudioTapCapturer.wireFormat(
                from: asbd(bitsPerChannel: 16, flags: kAudioFormatFlagIsSignedInteger)
            )
        )
    }

    func testDoublePrecisionFloatIsRejected() {
        XCTAssertThrowsError(try CoreAudioTapCapturer.wireFormat(from: asbd(bitsPerChannel: 64)))
    }

    func testCompressedFormatIsRejected() {
        XCTAssertThrowsError(try CoreAudioTapCapturer.wireFormat(from: asbd(formatID: kAudioFormatMPEG4AAC)))
    }

    func testZeroChannelsIsRejected() {
        XCTAssertThrowsError(try CoreAudioTapCapturer.wireFormat(from: asbd(channels: 0)))
    }

    func testZeroSampleRateIsRejected() {
        XCTAssertThrowsError(try CoreAudioTapCapturer.wireFormat(from: asbd(sampleRate: 0)))
    }
}
