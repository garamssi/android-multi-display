import Foundation
import CoreGraphics
import CGVirtualDisplayBridge

public final class VirtualDisplayManager: VirtualDisplayManaging, @unchecked Sendable {
    private let bridge = VirtualDisplayBridge()
    private let lock = NSLock()

    public init() {}

    public func createDisplay(config: DisplayConfig) async throws {
        try lock.withLock {
            do {
                try bridge.createDisplay(
                    withWidth: UInt(config.width),
                    height: UInt(config.height),
                    ppi: 220,
                    name: "DeskLink Display"
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

    /// The CGDirectDisplayID of the virtual display, or 0 if inactive.
    public var displayID: UInt32 {
        lock.withLock { bridge.displayID }
    }

    /// The display's actual active pixel resolution, or nil if inactive/unavailable.
    /// Used to surface a private-API mode fallback (e.g. the 1280x800 bug) in the logs
    /// without failing the connection.
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
