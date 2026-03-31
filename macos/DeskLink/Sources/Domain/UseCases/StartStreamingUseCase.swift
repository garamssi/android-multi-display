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

    public func execute(config: DisplayConfig) async throws {
        try await displayManager.createDisplay(config: config)
        try await encoder.configure(config: config)
        try await streamServer.start(port: ProtocolConstants.portVideo)
    }

    public func stop() async {
        await streamServer.stop()
        await screenCapturer.stopCapture()
        await displayManager.destroyDisplay()
    }
}
