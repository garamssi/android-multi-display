import Foundation
import Observation
import AppKit

@MainActor
@Observable
public final class SettingsViewModel {

    public private(set) var accessibilityGranted = false
    public private(set) var screenRecordingGranted = false

    public private(set) var localNetworkAddresses: [String] = []

    public private(set) var diagnosticsStatus: String?

    public private(set) var logText: String = ""

    public private(set) var logLines: [DiagnosticLogLine] = []

    private let permissions: PermissionsManaging

    public init(permissions: PermissionsManaging = SystemPermissions()) {
        self.permissions = permissions
        self.wifiEnabled = TransportSettings.wifiEnabled
        refresh()
    }

    public var verboseLogging: Bool {
        get { Log.isVerbose }
        set { Log.isVerbose = newValue }
    }

    public private(set) var pairingPin: String = PairingPin.current

    public private(set) var pairingSecondsRemaining: Int = PairingPin.secondsRemaining()

    public func tickPairing(connected: Bool) {
        guard !connected else { return }
        PairingPin.rotateIfExpired()
        pairingPin = PairingPin.current
        pairingSecondsRemaining = PairingPin.secondsRemaining()
    }

    public var wifiEnabled: Bool {
        didSet { TransportSettings.wifiEnabled = wifiEnabled }
    }

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

    public func refreshLogs() {
        diagnosticsStatus = "Loading…"
        Task {
            let text = await Self.recentLogText()
            logText = text
            logLines = DiagnosticLogParser.parse(text)
            let lines = text.split(separator: "\n").count
            diagnosticsStatus = "Last 5 min · \(lines) line\(lines == 1 ? "" : "s")"
        }
    }

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

    public func copyAddress(_ address: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(address, forType: .string)
    }

    // MARK: - Private

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
