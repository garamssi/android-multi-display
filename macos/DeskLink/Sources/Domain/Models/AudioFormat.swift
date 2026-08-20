import Foundation

/// Describes the PCM layout carried on the audio channel, as announced by
/// AUDIO_CONFIG (0x30).
///
/// Header fields are big-endian like the rest of the protocol. The PCM block that
/// follows in each AUDIO_FRAME is deliberately NOT byte-swapped: it is an opaque
/// payload whose layout this type declares, exactly as a VIDEO_FRAME carries opaque
/// Annex-B NAL bytes. Declaring it little-endian lets the Android client pass the bytes
/// straight to `AudioTrack`, whose `ENCODING_PCM_16BIT` is native-endian (little-endian
/// on the Arm and x86 devices Android ships on), so the conversion happens once on the
/// Mac instead of per sample on the tablet.
///
/// Only fully playable formats can be constructed: a zero sample rate or channel count
/// would divide by zero downstream, and a bit depth that disagrees with the declared
/// encoding would silently mis-frame every sample. Both are rejected at construction so
/// no later code has to defend against them.
public struct AudioFormat: Sendable, Equatable {

    /// PCM sample layout. The wire value is the AUDIO_CONFIG `encoding` byte.
    public enum Encoding: UInt8, Sendable {
        /// Interleaved signed 16-bit little-endian PCM.
        case pcmSignedLittleEndian16 = 0x01

        /// Bit depth implied by the encoding. The wire also carries `bitsPerSample`;
        /// the two are cross-checked so a mismatched pair cannot be accepted.
        public var bitsPerSample: UInt8 {
            switch self {
            case .pcmSignedLittleEndian16: return 16
            }
        }
    }

    public let sampleRate: UInt32
    public let channelCount: UInt8
    public let encoding: Encoding

    /// Bit depth of one sample, defined by the encoding.
    public var bitsPerSample: UInt8 { encoding.bitsPerSample }

    /// AUDIO_CONFIG payload layout.
    public static let sampleRateFieldSize = 4
    public static let serializedSize = sampleRateFieldSize + 3

    private static let bitsPerByte = 8

    /// Fails when the format could not be played: a zero sample rate makes every
    /// duration zero, and zero channels makes `bytesPerFrame` zero.
    public init?(sampleRate: UInt32, channelCount: UInt8, encoding: Encoding) {
        guard sampleRate > 0, channelCount > 0 else { return nil }
        self.sampleRate = sampleRate
        self.channelCount = channelCount
        self.encoding = encoding
    }

    /// Bytes occupied by one sample frame (one sample for every channel). Every
    /// buffer-duration and lip-sync calculation is expressed in this unit, so it is
    /// never inlined at a call site. Guaranteed non-zero by the initializer.
    public var bytesPerFrame: Int {
        Int(channelCount) * Int(bitsPerSample) / Self.bitsPerByte
    }

    /// Playback duration of `frameCount` sample frames, in microseconds.
    ///
    /// Truncating division: at 44.1 kHz this loses a fraction of a microsecond per
    /// call. Summing per-chunk durations therefore accumulates drift (~41 ppm, about
    /// 25 ms over ten minutes), so a playback position must be derived from a running
    /// FRAME COUNT, not from adding these values up.
    public func durationUs(frameCount: Int) -> Int64 {
        Int64(frameCount) * MicrosecondsPerSecond.value / Int64(sampleRate)
    }

    /// Sample frames that `byteCount` bytes of this format represent.
    public func frameCount(forByteCount byteCount: Int) -> Int {
        byteCount / bytesPerFrame
    }

    /// Serializes the AUDIO_CONFIG (0x30) payload. Framing (length + type) is applied
    /// by `PacketFramer`.
    public func serialize() -> Data {
        var payload = Data(capacity: Self.serializedSize)
        withUnsafeBytes(of: sampleRate.bigEndian) { payload.append(contentsOf: $0) }
        payload.append(channelCount)
        payload.append(bitsPerSample)
        payload.append(encoding.rawValue)
        return payload
    }

    /// Parses an AUDIO_CONFIG payload, rejecting anything unplayable: a short payload,
    /// an unknown encoding, a zero sample rate or channel count, or a `bitsPerSample`
    /// that contradicts the declared encoding.
    public static func deserialize(_ payload: Data) -> AudioFormat? {
        guard payload.count >= serializedSize else { return nil }

        // Read through withUnsafeBytes with an explicit offset, matching
        // `TouchDeserializer`: it parses the fixed header without copying the payload,
        // which for an AUDIO_FRAME-sized block would mean copying all the PCM too.
        return payload.withUnsafeBytes { raw -> AudioFormat? in
            let sampleRate = UInt32(bigEndian: raw.loadUnaligned(fromByteOffset: 0, as: UInt32.self))
            let channelCount = raw.loadUnaligned(fromByteOffset: sampleRateFieldSize, as: UInt8.self)
            let bitsPerSample = raw.loadUnaligned(fromByteOffset: sampleRateFieldSize + 1, as: UInt8.self)
            let encodingByte = raw.loadUnaligned(fromByteOffset: sampleRateFieldSize + 2, as: UInt8.self)

            guard let encoding = Encoding(rawValue: encodingByte),
                  bitsPerSample == encoding.bitsPerSample
            else { return nil }

            return AudioFormat(sampleRate: sampleRate, channelCount: channelCount, encoding: encoding)
        }
    }
}

/// Microseconds in one second. Named because the conversion appears across the audio,
/// video and sync paths and a bare literal there reads as a magic number.
public enum MicrosecondsPerSecond {
    public static let value: Int64 = 1_000_000
}
