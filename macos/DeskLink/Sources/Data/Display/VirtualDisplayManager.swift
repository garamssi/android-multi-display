import Foundation
import CoreGraphics
import CGVirtualDisplayBridge

public final class VirtualDisplayManager: VirtualDisplayManaging, @unchecked Sendable {
    // Nominal pixel density for the virtual display. The tablet's real density is not
    // used: the mode is created in pixels and the client scales to its own panel, so this
    // only affects how macOS labels the display, not what is captured.
    private static let virtualDisplayPPI: UInt = 220
    private static let virtualDisplayName = "DeskLink Display"

    private let bridge = VirtualDisplayBridge()
    private let lock = NSLock()

    public init() {}

    public func createDisplay(config: DisplayConfig) async throws {
        try lock.withLock {
            do {
                try bridge.createDisplay(
                    withWidth: UInt(config.width),
                    height: UInt(config.height),
                    ppi: Self.virtualDisplayPPI,
                    // The mode's refresh rate must cover the negotiated frame rate: a
                    // 60 Hz mode cannot deliver the 120 fps the client can ask for, and
                    // the capture would silently top out at 60.
                    refreshRate: Double(config.fps),
                    name: Self.virtualDisplayName
                )
            } catch {
                throw mapError(error as NSError)
            }
        }
    }

    public func destroyDisplay() async {
        lock.withLock {
            bridge.destroyDisplay()
        }
    }

    public func updateResolution(width: Int, height: Int) async throws {
        try lock.withLock {
            do {
                try bridge.updateResolution(
                    withWidth: UInt(width),
                    height: UInt(height)
                )
            } catch {
                throw mapError(error as NSError)
            }
        }
    }

    public var isDisplayActive: Bool {
        get async {
            lock.withLock { bridge.isActive }
        }
    }

    public var displayID: UInt32 {
        lock.withLock { bridge.displayID }
    }

    public var activeResolution: (width: Int, height: Int)? {
        let id = lock.withLock { bridge.displayID }
        guard id != 0, let mode = CGDisplayCopyDisplayMode(id) else { return nil }
        return (mode.pixelWidth, mode.pixelHeight)
    }

    private func mapError(_ nsError: NSError) -> ConnectionError {
        switch nsError.code {
        case VirtualDisplayBridgeError.apiNotAvailable.rawValue:
            return .displayCreateFailed
        case VirtualDisplayBridgeError.creationFailed.rawValue:
            return .displayCreateFailed
        case VirtualDisplayBridgeError.invalidResolution.rawValue:
            return .displayResolutionInvalid
        default:
            return .displayCreateFailed
        }
    }
}
