import Foundation

public final class StartStreamingUseCase: Sendable {
    private let displayManager: any VirtualDisplayManaging
    private let screenCapturer: any ScreenCapturing
    private let encoder: any VideoEncoding
    private let streamServer: any StreamServing

    /// Fired when a client connects (or reconnects) its video socket and streaming
    /// (re)starts — the authoritative "client is present and streaming" signal. Lets the
    /// coordinator re-enter `.connected` after a video-only reconnect (e.g. the tablet
    /// returning from the background) without needing a fresh control handshake.
    private let onClientPresent: (@Sendable () async -> Void)?

    /// Fired when the client's video stream dies on its own (a send/encode failure —
    /// i.e. the client went away), as opposed to being cancelled by a reconnect/teardown.
    /// This is the authoritative "client gone" signal the coordinator uses to end a
    /// session, instead of the control-channel keep-alive heartbeat.
    private let onClientGone: (@Sendable () async -> Void)?

    public init(
        displayManager: any VirtualDisplayManaging,
        screenCapturer: any ScreenCapturing,
        encoder: any VideoEncoding,
        streamServer: any StreamServing,
        onClientPresent: (@Sendable () async -> Void)? = nil,
        onClientGone: (@Sendable () async -> Void)? = nil
    ) {
        self.displayManager = displayManager
        self.screenCapturer = screenCapturer
        self.encoder = encoder
        self.streamServer = streamServer
        self.onClientPresent = onClientPresent
        self.onClientGone = onClientGone
    }

    /// Runs the video streaming loop, one connected client at a time.
    ///
    /// The composition root (`ServerCoordinator.bootStreaming`) has already created
    /// the virtual display, configured the encoder, and started the video server
    /// listening — so this method must NOT do any of those (re-starting the
    /// already-bound server would error). It only accepts client connections and
    /// drives the capture → encode → send loop, sending VIDEO_CONFIG before the
    /// first VIDEO_FRAME (S-C3) and serializing each frame with its header (S-C2).
    public func execute(config: DisplayConfig, displayID: UInt32) async throws {
        // Restart the capture -> encode -> send loop on EVERY (re)connection. Each
        // connection (including a client that reopens the video socket after returning
        // from the background) yields a fresh clientConnections event; restarting makes
        // `streamToClient` force a new keyframe + VIDEO_CONFIG so the client always gets
        // an IDR to start decoding. `streamToClient` runs in a child task so this loop
        // is NOT blocked inside it and can react to a reconnect immediately — otherwise
        // the previous loop keeps sending mid-GOP frames (no IDR) to the new socket and
        // the client stays black.
        var streamTask: Task<Void, Never>?
        for await _ in streamServer.clientConnections {
            Log.info(.stream, "stream: (re)connection -> restarting stream with keyframe")
            // Tear the previous stream down before starting the next. The prior task is
            // already unblocking (its connection was cancelled when this one replaced
            // it), so awaiting it can't hang; this serializes capture start/stop so the
            // two streams never overlap.
            streamTask?.cancel()
            await streamTask?.value
            await screenCapturer.stopCapture()
            // A client is (re)connected and about to stream — surface it so the UI can
            // (re)enter connected even for a video-only reconnect with no new handshake.
            await onClientPresent?()
            streamTask = Task { [self] in
                do {
                    try await streamToClient(config: config, displayID: displayID)
                    // Frames ended without error — this happens on an intentional
                    // capture stop (teardown/reconnect), not a client-gone event.
                    Log.info(.stream, "stream: capture loop ended")
                } catch is CancellationError {
                    // Superseded by a new connection or a teardown — not a client loss.
                } catch {
                    // A send/encode failure means the client's video socket is gone.
                    // This — not the control keep-alive — is what ends a session.
                    Log.error(.stream, "stream: streamToClient error: \(error)")
                    await screenCapturer.stopCapture()
                    await onClientGone?()
                }
            }
        }
        streamTask?.cancel()
        await streamTask?.value
        await screenCapturer.stopCapture()
    }

    /// Captures → encodes → sends frames to the currently-connected client. Forces
    /// an initial keyframe and sends VIDEO_CONFIG (CSD) before the first VIDEO_FRAME
    /// so the decoder can initialize and render immediately.
    private func streamToClient(config: DisplayConfig, displayID: UInt32) async throws {
        await encoder.forceKeyframe()
        var didSendConfig = false
        var frameCount = 0

        let frames = screenCapturer.startCapture(displayID: displayID, fps: config.fps)
        for try await frame in frames {
            let encoded = try await encoder.encode(frame: frame)

            // Send VIDEO_CONFIG (0x11) once, before the first VIDEO_FRAME, as soon
            // as the encoder has produced CSD (available after the first keyframe).
            if !didSendConfig, let csd = await encoder.codecConfigData, !csd.isEmpty {
                let codecID = VideoConfigMessage.CodecID(config.codec)
                let configPayload = try VideoConfigMessage.serialize(codec: codecID, config: csd)
                try await streamServer.send(data: configPayload, type: .videoConfig)
                didSendConfig = true
                Log.info(.stream, "stream: sent VIDEO_CONFIG (\(csd.count) bytes)")
            }

            // VIDEO_FRAME (0x10): serialized header (ts/flags/frameNo) + Annex-B NAL.
            try await streamServer.send(data: encoded.serialize(), type: .videoFrame)
            frameCount += 1
            if frameCount <= 3 || frameCount % 120 == 0 {
                Log.debug(.stream, "stream: sent VIDEO_FRAME #\(frameCount) (\(encoded.data.count) bytes, key=\(encoded.isKeyframe))")
            }
        }
    }
}
