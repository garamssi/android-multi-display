import Foundation

public protocol PermissionsManaging: Sendable {
    func isAccessibilityGranted() -> Bool
    func isScreenRecordingGranted() -> Bool

    func requestAccessibility()
    func requestScreenRecording()

    func openAccessibilitySettings()
    func openScreenRecordingSettings()
}
