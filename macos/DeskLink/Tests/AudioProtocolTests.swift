import XCTest
@testable import DeskLink

/// Wire-format tests for the audio channel (AUDIO_CONFIG 0x30 / AUDIO_FRAME 0x31).
final class AudioProtocolTests: XCTestCase {

    private let stereo48k = AudioFormat(sampleRate: 48_000, channelCount: 2, encoding: .pcmSignedLittleEndian16)!

    // MARK: - AudioFormat construction

    func testRejectsZeroSampleRate() {
        XCTAssertNil(AudioFormat(sampleRate: 0, channelCount: 2, encoding: .pcmSignedLittleEndian16))
    }

    /// Zero channels would make `bytesPerFrame` zero and divide by zero downstream.
    func testRejectsZeroChannelCount() {
        XCTAssertNil(AudioFormat(sampleRate: 48_000, channelCount: 0, encoding: .pcmSignedLittleEndian16))
    }

    func testBitsPerSampleComesFromEncoding() {
        XCTAssertEqual(stereo48k.bitsPerSample, 16)
        XCTAssertEqual(AudioFormat.Encoding.pcmSignedLittleEndian16.bitsPerSample, 16)
    }

    // MARK: - AUDIO_CONFIG wire format

    func testAudioFormatHeaderSizeMatchesSpec() {
        // sampleRate(4) + channelCount(1) + bitsPerSample(1) + encoding(1)
        XCTAssertEqual(AudioFormat.serializedSize, 7)
    }

    func testAudioFormatSerializesGoldenVector() {
        // 48000 = 0x0000BB80
        XCTAssertEqual(stereo48k.serialize(), Data([0x00, 0x00, 0xBB, 0x80, 0x02, 0x10, 0x01]))
    }

    func testAudioFormatRoundTrip() {
        let mono44k = AudioFormat(sampleRate: 44_100, channelCount: 1, encoding: .pcmSignedLittleEndian16)!
        XCTAssertEqual(AudioFormat.deserialize(mono44k.serialize()), mono44k)
    }

    func testAudioFormatDeserializeRejectsShortPayload() {
        XCTAssertNil(AudioFormat.deserialize(Data([0x00, 0x00, 0xBB, 0x80, 0x02, 0x10])))
    }

    func testAudioFormatDeserializeRejectsUnknownEncoding() {
        XCTAssertNil(AudioFormat.deserialize(Data([0x00, 0x00, 0xBB, 0x80, 0x02, 0x10, 0xFF])))
    }

    func testAudioFormatDeserializeRejectsZeroSampleRate() {
        XCTAssertNil(AudioFormat.deserialize(Data([0x00, 0x00, 0x00, 0x00, 0x02, 0x10, 0x01])))
    }

    func testAudioFormatDeserializeRejectsZeroChannelCount() {
        XCTAssertNil(AudioFormat.deserialize(Data([0x00, 0x00, 0xBB, 0x80, 0x00, 0x10, 0x01])))
    }

    /// A bit depth that contradicts the declared encoding must be refused: accepting it
    /// would frame every sample at the wrong width.
    func testAudioFormatDeserializeRejectsBitDepthContradictingEncoding() {
        XCTAssertNil(AudioFormat.deserialize(Data([0x00, 0x00, 0xBB, 0x80, 0x02, 0x18, 0x01])))
        XCTAssertNil(AudioFormat.deserialize(Data([0x00, 0x00, 0xBB, 0x80, 0x02, 0x08, 0x01])))
    }

    /// `PacketFramer` and the socket layer both hand out `Data` slices; parsing must not
    /// assume a zero start index.
    func testAudioFormatDeserializeAcceptsSlicedInput() {
        let padded = Data([0xDE, 0xAD, 0xBE, 0xEF]) + stereo48k.serialize()
        XCTAssertEqual(AudioFormat.deserialize(padded.dropFirst(4)), stereo48k)
    }

    // MARK: - Format arithmetic

    func testBytesPerFrame() {
        XCTAssertEqual(stereo48k.bytesPerFrame, 4)
        XCTAssertEqual(AudioFormat(sampleRate: 48_000, channelCount: 1, encoding: .pcmSignedLittleEndian16)!.bytesPerFrame, 2)
    }

    func testFrameCountToDurationMicros() {
        XCTAssertEqual(stereo48k.durationUs(frameCount: 48_000), 1_000_000)
        XCTAssertEqual(stereo48k.durationUs(frameCount: 480), 10_000)
    }

    /// 44.1 kHz does not divide evenly into microseconds. The truncation is documented
    /// and acceptable per call, but this test records WHY a playout position must come
    /// from a running frame count instead of summing these values.
    func testDurationTruncatesAtNonIntegralSampleRate() {
        let mono44k = AudioFormat(sampleRate: 44_100, channelCount: 1, encoding: .pcmSignedLittleEndian16)!
        // 1024 frames at 44100 Hz = 23219.95... us -> truncated to 23219.
        XCTAssertEqual(mono44k.durationUs(frameCount: 1_024), 23_219)
        // Summing 100 chunks drifts below the exact value for 102400 frames.
        let summed = (0..<100).reduce(Int64(0)) { total, _ in total + mono44k.durationUs(frameCount: 1_024) }
        XCTAssertLessThan(summed, mono44k.durationUs(frameCount: 102_400))
    }

    func testFrameCountFromByteCount() {
        XCTAssertEqual(stereo48k.frameCount(forByteCount: 400), 100)
    }

    // MARK: - AUDIO_FRAME wire format

    func testAudioChunkHeaderSizeMatchesSpec() {
        XCTAssertEqual(AudioChunk.headerSize, 12)
    }

    func testAudioChunkSerializesGoldenVector() {
        let pcm = Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08])
        let chunk = AudioChunk(pcm: pcm, timestampUs: 1_000_000, frameCount: 2)
        // ts 1_000_000 = 0x00000000000F4240, frameCount 2 = 0x00000002
        XCTAssertEqual(
            chunk.serialize(),
            Data([0x00, 0x00, 0x00, 0x00, 0x00, 0x0F, 0x42, 0x40])
                + Data([0x00, 0x00, 0x00, 0x02])
                + pcm
        )
    }

    func testAudioChunkDerivesFrameCountFromPayload() {
        // 8 bytes of 16-bit stereo = 2 sample frames.
        let chunk = AudioChunk(pcm: Data(repeating: 0, count: 8), timestampUs: 0, format: stereo48k)
        XCTAssertEqual(chunk.frameCount, 2)
        XCTAssertTrue(chunk.isConsistent(with: stereo48k))
    }

    func testAudioChunkPreservesNegativeTimestamp() throws {
        let chunk = AudioChunk(pcm: Data([0xAA, 0xBB]), timestampUs: -1, frameCount: 1)
        let serialized = chunk.serialize()
        XCTAssertEqual(serialized.prefix(8), Data(repeating: 0xFF, count: 8))
        XCTAssertEqual(try AudioChunk.deserialize(serialized).get().timestampUs, -1)
    }

    func testAudioChunkPreservesExtremeTimestamps() throws {
        for timestamp in [Int64.min, Int64.max, 0] {
            let chunk = AudioChunk(pcm: Data([0x01, 0x02]), timestampUs: timestamp, frameCount: 1)
            XCTAssertEqual(try AudioChunk.deserialize(chunk.serialize()).get().timestampUs, timestamp)
        }
    }

    func testAudioChunkRoundTrip() throws {
        let pcm = Data((0..<64).map { UInt8($0) })
        let chunk = AudioChunk(pcm: pcm, timestampUs: 123_456_789, frameCount: 16)
        let decoded = try AudioChunk.deserialize(chunk.serialize()).get()
        XCTAssertEqual(decoded.pcm, pcm)
        XCTAssertEqual(decoded.timestampUs, 123_456_789)
        XCTAssertEqual(decoded.frameCount, 16)
    }

    /// The parsed PCM must start at index 0. A `dropFirst` slice keeps a non-zero
    /// startIndex, so a caller doing `pcm[0]` or `pcm[0..<n]` would crash or read the
    /// header bytes — and `PacketFramer` establishes the zero-based convention.
    func testParsedPCMIsRebasedToZeroStartIndex() throws {
        let chunk = AudioChunk(pcm: Data([0x11, 0x22, 0x33, 0x44]), timestampUs: 7, frameCount: 1)
        let decoded = try AudioChunk.deserialize(chunk.serialize()).get()
        XCTAssertEqual(decoded.pcm.startIndex, 0)
        XCTAssertEqual(decoded.pcm[0], 0x11)
        XCTAssertEqual(decoded.pcm[0..<2], Data([0x11, 0x22]))
    }

    func testAudioChunkDeserializeAcceptsSlicedInput() throws {
        let chunk = AudioChunk(pcm: Data([0x11, 0x22]), timestampUs: 99, frameCount: 1)
        let padded = Data([0xDE, 0xAD]) + chunk.serialize()
        let decoded = try AudioChunk.deserialize(padded.dropFirst(2)).get()
        XCTAssertEqual(decoded.timestampUs, 99)
        XCTAssertEqual(decoded.pcm, Data([0x11, 0x22]))
        XCTAssertEqual(decoded.pcm.startIndex, 0)
    }

    /// A truncated header and a frame carrying no audio are different problems: one is a
    /// protocol violation, the other is a frame to skip. Collapsing both into nil left
    /// the caller unable to tell them apart.
    func testAudioChunkDistinguishesTruncatedHeaderFromEmptyPayload() {
        XCTAssertEqual(
            AudioChunk.deserialize(Data(repeating: 0, count: AudioChunk.headerSize - 1)),
            .failure(.truncatedHeader)
        )
        XCTAssertEqual(
            AudioChunk.deserialize(Data(repeating: 0, count: AudioChunk.headerSize)),
            .failure(.emptyPayload)
        )
    }

    // MARK: - frameCount consistency

    /// A frame claiming a duration its PCM cannot support would skew the client's
    /// playout prediction, which is exactly what lip-sync depends on.
    func testInconsistentFrameCountIsDetected() {
        let overstated = AudioChunk(pcm: Data(repeating: 0, count: 8), timestampUs: 0, frameCount: UInt32.max)
        XCTAssertFalse(overstated.isConsistent(with: stereo48k))

        let understated = AudioChunk(pcm: Data(repeating: 0, count: 8), timestampUs: 0, frameCount: 0)
        XCTAssertFalse(understated.isConsistent(with: stereo48k))

        let exact = AudioChunk(pcm: Data(repeating: 0, count: 8), timestampUs: 0, frameCount: 2)
        XCTAssertTrue(exact.isConsistent(with: stereo48k))
    }

    // MARK: - Channel wiring

    func testAudioMessageTypesMatchSpec() {
        XCTAssertEqual(MessageType.audioConfig.rawValue, 0x30)
        XCTAssertEqual(MessageType.audioFrame.rawValue, 0x31)
    }

    func testAudioPortFollowsInputPort() {
        XCTAssertEqual(ProtocolConstants.portAudio, ProtocolConstants.portInput + 1)
        XCTAssertEqual(ProtocolConstants.portAudio, 7103)
    }

    // MARK: - Real wire round trip

    /// Exercises the audio payloads through the actual framing the socket layer uses,
    /// the way the video channel is already covered.
    func testAudioConfigSurvivesFramingRoundTrip() throws {
        let framed = try PacketFramer.frame(type: .audioConfig, payload: stereo48k.serialize())
        guard case .success(let type, let payload, let remaining) = PacketFramer.unframe(buffer: framed) else {
            return XCTFail("unframe failed")
        }
        XCTAssertEqual(type, .audioConfig)
        XCTAssertTrue(remaining.isEmpty)
        XCTAssertEqual(AudioFormat.deserialize(payload), stereo48k)
    }

    func testAudioFrameSurvivesFramingRoundTrip() throws {
        let pcm = Data((0..<400).map { UInt8($0 % 256) })
        let chunk = AudioChunk(pcm: pcm, timestampUs: 987_654_321, format: stereo48k)
        let framed = try PacketFramer.frame(type: .audioFrame, payload: chunk.serialize())

        guard case .success(let type, let payload, _) = PacketFramer.unframe(buffer: framed) else {
            return XCTFail("unframe failed")
        }
        XCTAssertEqual(type, .audioFrame)

        let decoded = try AudioChunk.deserialize(payload).get()
        XCTAssertEqual(decoded.pcm, pcm)
        XCTAssertEqual(decoded.timestampUs, 987_654_321)
        XCTAssertEqual(decoded.frameCount, 100)
        XCTAssertTrue(decoded.isConsistent(with: stereo48k))
    }

    /// Two chunks back-to-back in one buffer must both parse — the audio channel is a
    /// stream, so coalesced packets are the normal case, not an edge case.
    func testBackToBackAudioFramesParseFromOneBuffer() throws {
        let first = AudioChunk(pcm: Data([0x01, 0x02, 0x03, 0x04]), timestampUs: 1_000, format: stereo48k)
        let second = AudioChunk(pcm: Data([0x05, 0x06, 0x07, 0x08]), timestampUs: 2_000, format: stereo48k)
        let buffer = try PacketFramer.frame(type: .audioFrame, payload: first.serialize())
            + PacketFramer.frame(type: .audioFrame, payload: second.serialize())

        guard case .success(_, let firstPayload, let remaining) = PacketFramer.unframe(buffer: buffer) else {
            return XCTFail("first unframe failed")
        }
        XCTAssertEqual(try AudioChunk.deserialize(firstPayload).get().timestampUs, 1_000)

        guard case .success(_, let secondPayload, _) = PacketFramer.unframe(buffer: remaining) else {
            return XCTFail("second unframe failed")
        }
        XCTAssertEqual(try AudioChunk.deserialize(secondPayload).get().timestampUs, 2_000)
    }
}
