import Foundation
import ApplicationServices
import CoreGraphics
import AppKit

public final class SystemPermissions: PermissionsManaging {

    public init() {}

    public func isAccessibilityGranted() -> Bool {
        AXIsProcessTrusted()
    }

    public func isScreenRecordingGranted() -> Bool {
        CGPreflightScreenCaptureAccess()
    }

    public func requestAccessibility() {
        _ = CGEventInjector.requestAccessibility()
    }

    public func requestScreenRecording() {
        _ = CGRequestScreenCaptureAccess()
    }

    public func openAccessibilitySettings() {
        open(Self.accessibilityURL)
    }

    public func openScreenRecordingSettings() {
        open(Self.screenRecordingURL)
    }

    // MARK: - Deep links

    static let accessibilityURL = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"
    static let screenRecordingURL = "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture"
    static let privacyRootURL = "x-apple.systempreferences:com.apple.preference.security"

    private func open(_ anchor: String) {
        if let url = URL(string: anchor), NSWorkspace.shared.open(url) { return }
        if let fallback = URL(string: Self.privacyRootURL) {
            NSWorkspace.shared.open(fallback)
        }
    }
}
