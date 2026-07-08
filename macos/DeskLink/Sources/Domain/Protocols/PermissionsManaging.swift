import Foundation

/// Abstraction over the two macOS privacy permissions DeskLink needs:
/// - Accessibility (TCC): required to inject synthetic mouse/scroll events.
/// - Screen Recording (TCC): required for ScreenCaptureKit to capture the display.
///
/// Kept behind a protocol so the presentation layer (the Settings screen) depends on
/// this abstraction, not on `ApplicationServices` / `CoreGraphics` / `AppKit` directly,
/// and so the settings view-model can be unit-tested with a fake.
public protocol PermissionsManaging: Sendable {
    /// Whether the process is currently trusted for Accessibility (input injection).
    func isAccessibilityGranted() -> Bool
    /// Whether the process currently has Screen Recording access (capture).
    func isScreenRecordingGranted() -> Bool

    /// Prompts for Accessibility (and registers the app in the Accessibility list).
    func requestAccessibility()
    /// Prompts for Screen Recording access.
    func requestScreenRecording()

    /// Opens System Settings at the Accessibility pane.
    func openAccessibilitySettings()
    /// Opens System Settings at the Screen Recording pane.
    func openScreenRecordingSettings()
}
