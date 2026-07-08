import Foundation
import ApplicationServices
import CoreGraphics
import AppKit

/// `PermissionsManaging` backed by the real macOS APIs.
///
/// - Accessibility: `AXIsProcessTrusted()` checks; the prompt reuses
///   `CGEventInjector.requestAccessibility()` so there is a single source of truth for
///   how the app registers itself in the Accessibility list.
/// - Screen Recording: `CGPreflightScreenCaptureAccess()` checks WITHOUT prompting;
///   `CGRequestScreenCaptureAccess()` prompts (both available since macOS 10.15).
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

    /// System Settings deep-link anchors. Stable across Ventura–Sequoia; if a future
    /// macOS renames the anchor, only these constants change. `open` falls back to the
    /// Privacy & Security root so the button still lands the user somewhere useful.
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
