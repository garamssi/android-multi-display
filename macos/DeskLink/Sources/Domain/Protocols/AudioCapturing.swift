import Foundation

/// One live audio capture: the negotiated PCM layout plus the stream of captured
/// chunks. The format is resolved before any chunk is emitted so the server can send
/// AUDIO_CONFIG to the client first — a client that receives PCM before it knows the
/// sample rate cannot start playback.
public struct AudioCaptureSession: Sendable {
    public let format: AudioFormat
    public let chunks: AsyncStream<AudioChunk>

    public init(format: AudioFormat, chunks: AsyncStream<AudioChunk>) {
        self.format = format
        self.chunks = chunks
    }
}

public protocol AudioCapturing: Sendable {
    /// Starts capturing system audio. While capturing, local playback on the Mac is
    /// suppressed — the audio is routed to this capture instead of the speakers.
    func startCapture() async throws -> AudioCaptureSession
    func stopCapture() async
}
