import Foundation

/// One block of captured PCM with the capture timestamp that anchors it to the video
/// stream, carried as an AUDIO_FRAME (0x31) payload.
///
/// `timestampUs` is on the SAME axis as `EncodedFrame.timestampUs` — host uptime in
/// microseconds. That shared axis is the whole basis of lip-sync: the client can only
/// align audio against video if both timestamps are comparable.
public struct AudioChunk: Sendable, Equatable {

    /// Why a chunk could not be parsed. Distinguished because the two cases call for
    /// different handling: a malformed header is a protocol violation worth tearing the
    /// connection down for, while a frame carrying no audio can simply be skipped.
    public enum ParseFailure: Error, Equatable {
        case truncatedHeader
        case emptyPayload
    }

    /// Interleaved PCM in the layout announced by AUDIO_CONFIG.
    public let pcm: Data
    /// Capture time of the first sample frame, in microseconds of host uptime.
    public let timestampUs: Int64
    /// Number of sample frames (per channel) — not bytes and not total samples.
    public let frameCount: UInt32

    /// Fixed AUDIO_FRAME header field sizes.
    public static let timestampFieldSize = 8
    public static let frameCountFieldSize = 4
    public static let headerSize = timestampFieldSize + frameCountFieldSize

    public init(pcm: Data, timestampUs: Int64, frameCount: UInt32) {
        self.pcm = pcm
        self.timestampUs = timestampUs
        self.frameCount = frameCount
    }

    /// Builds a chunk whose `frameCount` is DERIVED from the payload, so a sender
    /// cannot emit a header that disagrees with its own PCM.
    public init(pcm: Data, timestampUs: Int64, format: AudioFormat) {
        self.init(
            pcm: pcm,
            timestampUs: timestampUs,
            frameCount: UInt32(format.frameCount(forByteCount: pcm.count))
        )
    }

    /// Whether the declared frame count matches the PCM actually carried.
    ///
    /// The receiver must check this before using `frameCount` for playback timing: a
    /// corrupt or hostile frame could otherwise claim any duration it likes and skew
    /// the client's playout prediction, on the one channel where timing accuracy is
    /// the entire point.
    public func isConsistent(with format: AudioFormat) -> Bool {
        pcm.count == Int(frameCount) * format.bytesPerFrame
    }

    /// Serializes the AUDIO_FRAME (0x31) payload:
    /// `[Timestamp int64 us BE (8)][FrameCount uint32 BE (4)][PCM...]`.
    public func serialize() -> Data {
        var payload = Data(capacity: Self.headerSize + pcm.count)
        withUnsafeBytes(of: UInt64(bitPattern: timestampUs).bigEndian) { payload.append(contentsOf: $0) }
        withUnsafeBytes(of: frameCount.bigEndian) { payload.append(contentsOf: $0) }
        payload.append(pcm)
        return payload
    }

    /// Parses an AUDIO_FRAME payload.
    ///
    /// The returned `pcm` is re-based to a zero start index via `subdata`, matching
    /// `PacketFramer`'s convention — a `dropFirst` slice keeps a non-zero `startIndex`,
    /// so any caller indexing it absolutely would read the wrong bytes or crash.
    public static func deserialize(_ payload: Data) -> Result<AudioChunk, ParseFailure> {
        guard payload.count >= headerSize else { return .failure(.truncatedHeader) }
        guard payload.count > headerSize else { return .failure(.emptyPayload) }

        // Parse the fixed header without copying the payload: `[UInt8](payload)` would
        // copy the entire PCM block on a path that runs hundreds of times a second.
        let (timestampUs, frameCount) = payload.withUnsafeBytes { raw -> (Int64, UInt32) in
            let rawTimestamp = UInt64(bigEndian: raw.loadUnaligned(fromByteOffset: 0, as: UInt64.self))
            let count = UInt32(
                bigEndian: raw.loadUnaligned(fromByteOffset: timestampFieldSize, as: UInt32.self)
            )
            return (Int64(bitPattern: rawTimestamp), count)
        }

        let pcmStart = payload.startIndex + headerSize
        return .success(
            AudioChunk(
                pcm: payload.subdata(in: pcmStart..<payload.endIndex),
                timestampUs: timestampUs,
                frameCount: frameCount
            )
        )
    }
}
