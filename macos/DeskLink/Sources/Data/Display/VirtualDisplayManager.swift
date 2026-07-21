import Foundation
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

    private func mapError(_ nsError: NSError) -> ConnectionError {
        switch nsError.code {
        case VirtualDisplayBridgeError.apiNotAvailable.rawValue:
            return .displayCreateFailed
        case VirtualDisplayBridgeError.creationFailed.rawValue:
            return .displayCreateFailed
        case VirtualDisplayBridgeError.invalidResolution.rawValue,
             VirtualDisplayBridgeError.resolutionMismatch.rawValue:
            return .displayResolutionInvalid
        case VirtualDisplayBridgeError.settingsApplyFailed.rawValue:
            return .displayCreateFailed
        default:
            return .displayCreateFailed
        }
    }
}
