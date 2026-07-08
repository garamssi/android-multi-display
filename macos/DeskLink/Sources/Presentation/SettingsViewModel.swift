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

    /// Transient status line for the diagnostics actions (nil = idle).
    public private(set) var diagnosticsStatus: String?

    /// The recent DeskLink log text shown in the in-app viewer (empty until loaded).
    public private(set) var logText: String = ""

    private let permissions: PermissionsManaging

    public init(permissions: PermissionsManaging = SystemPermissions()) {
        self.permissions = permissions
        refresh()
    }

    /// The persisted "verbose diagnostic logging" flag. Computed over [Log.isVerbose]
    /// so there's a single source of truth (no duplicated state to keep in sync).
    public var verboseLogging: Bool {
        get { Log.isVerbose }
        set { Log.isVerbose = newValue }
    }

    /// Re-reads both permission states. Called on open and periodically while the
    /// window is visible, so a change made in System Settings shows up here.
    public func refresh() {
        accessibilityGranted = permissions.isAccessibilityGranted()
        screenRecordingGranted = permissions.isScreenRecordingGranted()
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
