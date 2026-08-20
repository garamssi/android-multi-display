import Foundation

/// Streams captured Mac system audio to the connected client on the audio channel.
///
/// The tap is started only while a client is actually connected, and released as soon
/// as the client goes away. That lifetime is the whole safety story of this feature:
/// while the tap is being read the Mac's own speakers are silent, so a tap left running
/// with nobody to play the audio would leave the user with a mute Mac and no obvious
/// cause. Tying it to the connection means the speakers come back on their own.
public final class StreamAudioUseCase: Sendable {

    private let capturer: any AudioCapturing
    private let streamServer: any StreamServing

    public init(capturer: any AudioCapturing, streamServer: any StreamServing) {
        self.capturer = capturer
        self.streamServer = streamServer
    }

    /// Runs the audio streaming loop, one connected client at a time.
    ///
    /// Mirrors `StartStreamingUseCase`: every (re)connection restarts the stream, which
    /// re-announces AUDIO_CONFIG — a reconnected client is a fresh socket that has not
    /// seen the format yet, and PCM without a declared sample rate is unplayable.
    public func execute() async throws {
        var streamTask: Task<Void, Never>?

        for await _ in streamServer.clientConnections {
            Log.info(.stream, "audio: (re)connection -> restarting audio stream")
            // Tear the previous stream down first so two taps never overlap; a second
            // tap on the same output device is both wasteful and a second mute claim.
            streamTask?.cancel()
            await streamTask?.value
            await capturer.stopCapture()

            streamTask = Task { [self] in
                do {
                    try await streamToClient()
                    Log.info(.stream, "audio: capture loop ended (client gone)")
                } catch {
                    Log.error(.stream, "audio: streamToClient error: \(error)")
                }
                // Always release the tap, including on the failure path: an orphaned tap
                // keeps the Mac's speakers muted.
                await capturer.stopCapture()
            }
        }

        streamTask?.cancel()
        await streamTask?.value
        await capturer.stopCapture()
    }

    /// Announces the format, then forwards every captured chunk as an AUDIO_FRAME.
    private func streamToClient() async throws {
        let session = try await capturer.startCapture()

        // AUDIO_CONFIG (0x30) before any AUDIO_FRAME, per the protocol spec.
        try await streamServer.send(data: session.format.serialize(), type: .audioConfig)
        Log.info(
            .stream,
            "audio: sent AUDIO_CONFIG (\(session.format.sampleRate) Hz, \(session.format.channelCount) ch)"
        )

        var chunkCount = 0
        for await chunk in session.chunks {
            try Task.checkCancellation()
            // AUDIO_FRAME (0x31): host-clock timestamp + frame count + PCM.
            try await streamServer.send(data: chunk.serialize(), type: .audioFrame)
            chunkCount += 1
            if chunkCount <= 3 || chunkCount % 500 == 0 {
                Log.debug(.stream, "audio: sent AUDIO_FRAME #\(chunkCount) (\(chunk.frameCount) frames)")
            }
        }
    }
}
