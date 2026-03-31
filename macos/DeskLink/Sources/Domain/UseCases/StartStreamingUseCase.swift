import Foundation

public final class StartStreamingUseCase: Sendable {
    private let displayManager: any VirtualDisplayManaging
    private let screenCapturer: any ScreenCapturing
    private let encoder: any VideoEncoding
    private let streamServer: any StreamServing

    public init(
        displayManager: any VirtualDisplayManaging,
        screenCapturer: any ScreenCapturing,
        encoder: any VideoEncoding,
        streamServer: any StreamServing
    ) {
        self.displayManager = displayManager
        self.screenCapturer = screenCapturer
        self.encoder = encoder
        self.streamServer = streamServer
    }

    /// Initializes the streaming pipeline:
    /// 1. Create virtual display
    /// 2. Configure encoder
    /// 3. Start TCP server
    /// 4. Begin capture → encode → send loop
    public func execute(config: DisplayConfig, displayID: UInt32) async throws {
        try await displayManager.createDisplay(config: config)
        try await encoder.configure(config: config)
        try await streamServer.start(port: ProtocolConstants.portVideo)

        let frames = screenCapturer.startCapture(displayID: displayID, fps: config.fps)
        for try await frame in frames {
            let encoded = try await encoder.encode(frame: frame)
            try await streamServer.send(data: encoded.data, type: .videoFrame)
        }
    }

    public func stop() async {
        await screenCapturer.stopCapture()
        await streamServer.stop()
        await displayManager.destroyDisplay()
    }
}
