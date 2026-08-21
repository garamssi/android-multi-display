import Foundation

// Extend mode: adds a virtual display sized to the negotiated config and streams that.
public final class VirtualDisplaySource: StreamSourceProviding {

    private let displayManager: any VirtualDisplayManaging

    public init(displayManager: any VirtualDisplayManaging) {
        self.displayManager = displayManager
    }

    public func prepare(config: DisplayConfig) async throws -> StreamSource {
        try await displayManager.createDisplay(config: config)

        // Capture the size the display actually took, not the size requested: the private
        // display API can fall back to a different mode, and encoding a smaller picture
        // into a larger frame spends bitrate on upscaled pixels.
        let actual = displayManager.activeResolution
        return StreamSource(
            displayID: displayManager.displayID,
            captureWidth: actual?.width ?? config.width,
            captureHeight: actual?.height ?? config.height,
            acceptsInput: true
        )
    }

    public func teardown() async {
        await displayManager.destroyDisplay()
    }
}
