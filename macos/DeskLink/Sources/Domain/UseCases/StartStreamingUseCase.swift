import Foundation

public final class StartStreamingUseCase: Sendable {
    private let displayManager: any VirtualDisplayManaging
    private let screenCapturer: any ScreenCapturing
    private let encoder: any VideoEncoding
    private let streamServer: any StreamServing

    private let onClientPresent: (@Sendable () async -> Void)?

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

    // Display, encoder, and video server are already started by the composition root; do NOT re-start them here (re-binding the bound server errors).
    public func execute(config: DisplayConfig, displayID: UInt32) async throws {
        // streamToClient runs in a child task so this loop reacts to reconnects immediately and forces a fresh keyframe + VIDEO_CONFIG (IDR); blocking here leaves the new client black.
        var streamTask: Task<Void, Never>?
        for await _ in streamServer.clientConnections {
            Log.info(.stream, "stream: (re)connection -> restarting stream with keyframe")
            // Serialize capture start/stop: await the prior (already-cancelled, so it can't hang) task before stopping capture, so the two streams never overlap.
            streamTask?.cancel()
            await streamTask?.value
            await screenCapturer.stopCapture()
            await onClientPresent?()
            streamTask = Task { [self] in
                do {
                    try await streamToClient(config: config, displayID: displayID)
                    Log.info(.stream, "stream: capture loop ended")
                } catch is CancellationError {
                } catch {
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

    private func streamToClient(config: DisplayConfig, displayID: UInt32) async throws {
        await encoder.forceKeyframe()
        var didSendConfig = false
        var frameCount = 0

        let frames = screenCapturer.startCapture(displayID: displayID, fps: config.fps)
        for try await frame in frames {
            let encoded = try await encoder.encode(frame: frame)

            if !didSendConfig, let csd = await encoder.codecConfigData, !csd.isEmpty {
                let codecID = VideoConfigMessage.CodecID(config.codec)
                let configPayload = try VideoConfigMessage.serialize(codec: codecID, config: csd)
                try await streamServer.send(data: configPayload, type: .videoConfig)
                didSendConfig = true
                Log.info(.stream, "stream: sent VIDEO_CONFIG (\(csd.count) bytes)")
            }

            try await streamServer.send(data: encoded.serialize(), type: .videoFrame)
            frameCount += 1
            if frameCount <= 3 || frameCount % 120 == 0 {
                Log.debug(.stream, "stream: sent VIDEO_FRAME #\(frameCount) (\(encoded.data.count) bytes, key=\(encoded.isKeyframe))")
            }
        }
    }
}
