import Foundation

public struct EncodedFrame: Sendable {
    public let data: Data
    public let timestampUs: Int64
    public let isKeyframe: Bool
    public let frameNumber: UInt32

    public init(data: Data, timestampUs: Int64, isKeyframe: Bool, frameNumber: UInt32) {
        self.data = data
        self.timestampUs = timestampUs
        self.isKeyframe = isKeyframe
        self.frameNumber = frameNumber
    }

    /// VIDEO_FRAME (0x10) flag bits per protocol spec.
    public enum Flags {
        /// bit0 — frame is a keyframe (IDR).
        public static let isKeyframe: UInt8 = 0x01
        /// bit1 — payload includes codec-config change.
        public static let isConfig: UInt8 = 0x02
    }

    /// Size of the fixed VIDEO_FRAME header preceding the NAL data:
    /// Timestamp(8) + Flags(1) + FrameNumber(4) = 13 bytes.
    public static let headerSize = 13

    /// Serializes this frame into a VIDEO_FRAME (0x10) payload, per spec:
    /// `[Timestamp int64 us BE (8)][Flags u8 (1)][FrameNumber uint32 BE (4)][NAL...]`.
    ///
    /// Flags bit0 is set when `isKeyframe` is true. The NAL data is expected to be
    /// Annex-B formatted (start-code prefixed) — see `AnnexBConverter`.
    /// This is the videoFrame payload; framing (length + type 0x10) is applied
    /// separately by `PacketFramer`.
    public func serialize() -> Data {
        var payload = Data(capacity: Self.headerSize + data.count)

        // Timestamp: int64 microseconds, Big-Endian.
        withUnsafeBytes(of: UInt64(bitPattern: timestampUs).bigEndian) { payload.append(contentsOf: $0) }

        // Flags: bit0 = IS_KEYFRAME.
        let flags: UInt8 = isKeyframe ? Flags.isKeyframe : 0
        payload.append(flags)

        // Frame number: uint32, Big-Endian.
        withUnsafeBytes(of: frameNumber.bigEndian) { payload.append(contentsOf: $0) }

        // NAL data (Annex-B).
        payload.append(data)

        return payload
    }
}
