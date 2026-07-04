import Foundation

public protocol VideoEncoding: Sendable {
    func configure(config: DisplayConfig) async throws
    func encode(frame: VideoFrame) async throws -> EncodedFrame
    func updateBitrate(kbps: Int) async
    func forceKeyframe() async

    /// Codec-specific data (CSD) as Annex-B (HEVC: VPS+SPS+PPS), available after
    /// the first keyframe has been encoded. Used to build the VIDEO_CONFIG message.
    var codecConfigData: Data? { get async }
}
