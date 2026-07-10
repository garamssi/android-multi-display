import Foundation
import Observation
import AppKit

/// Presentation model for the Settings window: surfaces the two macOS permission
/// states and the diagnostic-logging toggle, and exposes intents to request/open them.
///
/// Platform calls go through `PermissionsManaging` (injected) so this is unit-testable
/// with a fake; the view stays a pure renderer.
@MainActor
@Observable
public final class SettingsViewModel {

    public private(set) var accessibilityGranted = false
    public private(set) var screenRecordingGranted = false

    /// The Mac's current non-loopback IPv4 addresses, shown so the user knows which
    /// address to type into the tablet when Wi-Fi (LAN) is enabled. Refreshed alongside
    /// the permission states.
    public private(set) var localNetworkAddresses: [String] = []

    /// Transient status line for the diagnostics actions (nil = idle).
    public private(set) var diagnosticsStatus: String?

    /// The recent DeskLink log text shown in the in-app viewer (empty until loaded).
    public private(set) var logText: String = ""

    private let permissions: PermissionsManaging

    public init(permissions: PermissionsManaging = SystemPermissions()) {
        self.permissions = permissions
        self.wifiEnabled = TransportSettings.wifiEnabled
        refresh()
    }

    /// The persisted "verbose diagnostic logging" flag. Computed over [Log.isVerbose]
    /// so there's a single source of truth (no duplicated state to keep in sync).
    public var verboseLogging: Bool {
        get { Log.isVerbose }
        set { Log.isVerbose = newValue }
    }

    /// The pairing PIN to show for LAN auth (stable; the tablet enters it once to pair).
    public var pairingPin: String { PairingPin.current }

    /// Generates a fresh pairing PIN (invalidates any prior pairing).
    public func regeneratePairingPin() {
        PairingPin.regenerate()
    }

    /// The "Allow Wi-Fi (LAN) connections" opt-in. Held as an observed stored property
    /// (so toggling it re-renders the dependent detail UI immediately) and persisted to
    /// [TransportSettings.wifiEnabled] on change. Read by the server at Start, so a
    /// change takes effect the next time the server is started.
    public var wifiEnabled: Bool {
        didSet { TransportSettings.wifiEnabled = wifiEnabled }
    }

    /// Re-reads both permission states and the local IPv4 addresses. Called on open and
    /// periodically while the window is visible, so a change made in System Settings (or
    /// a network change) shows up here.
    public func refresh() {
        accessibilityGranted = permissions.isAccessibilityGranted()
        screenRecordingGranted = permissions.isScreenRecordingGranted()
        localNetworkAddresses = NetworkInterfaces.localIPv4Addresses()
    }

    public func requestAccessibility() {
        permissions.requestAccessibility()
        refresh()
    }

    public func requestScreenRecording() {
        permissions.requestScreenRecording()
        refresh()
    }

    public func openAccessibilitySettings() {
        permissions.openAccessibilitySettings()
    }

    public func openScreenRecordingSettings() {
        permissions.openScreenRecordingSettings()
    }

    /// Loads the last 5 minutes of DeskLink unified-log lines into [logText] for the
    /// in-app viewer. Runs off the main actor; result is applied back on the main actor.
    public func refreshLogs() {
        diagnosticsStatus = "Loading…"
        Task {
            let text = await Self.recentLogText()
            logText = text
            let lines = text.split(separator: "\n").count
            diagnosticsStatus = "Last 5 min · \(lines) line\(lines == 1 ? "" : "s")"
        }
    }

    /// Copies whatever is currently shown in the viewer to the clipboard.
    public func copyLogs() {
        guard !logText.isEmpty else {
            diagnosticsStatus = "Nothing to copy yet — tap Refresh"
            return
        }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(logText, forType: .string)
        diagnosticsStatus = "Copied to clipboard"
    }

    public func openConsole() {
        NSWorkspace.shared.open(URL(fileURLWithPath: "/System/Applications/Utilities/Console.app"))
    }

    // MARK: - Private

    /// Runs `log show` for our subsystem off the main actor and returns the text.
    nonisolated private static func recentLogText() async -> String {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let process = Process()
                process.executableURL = URL(fileURLWithPath: "/usr/bin/log")
                process.arguments = [
                    "show", "--last", "5m", "--style", "compact",
                    "--predicate", "subsystem == \"\(Log.subsystem)\"",
                ]
                let pipe = Pipe()
                process.standardOutput = pipe
                process.standardError = pipe
                do {
                    try process.run()
                    let data = pipe.fileHandleForReading.readDataToEndOfFile()
                    process.waitUntilExit()
                    let text = String(decoding: data, as: UTF8.self)
                    continuation.resume(
                        returning: text.isEmpty
                            ? "No DeskLink log entries in the last 5 minutes."
                            : text
                    )
                } catch {
                    continuation.resume(returning: "Failed to read logs: \(error.localizedDescription)")
                }
            }
        }
    }
}
