import XCTest
@testable import DeskLink

@MainActor
final class SettingsViewModelTests: XCTestCase {

    /// Records intents and returns configurable permission states.
    private final class FakePermissions: PermissionsManaging, @unchecked Sendable {
        var accessibility = false
        var screenRecording = false
        var requestedAccessibility = 0
        var requestedScreenRecording = 0
        var openedAccessibility = 0
        var openedScreenRecording = 0

        func isAccessibilityGranted() -> Bool { accessibility }
        func isScreenRecordingGranted() -> Bool { screenRecording }
        func requestAccessibility() { requestedAccessibility += 1; accessibility = true }
        func requestScreenRecording() { requestedScreenRecording += 1; screenRecording = true }
        func openAccessibilitySettings() { openedAccessibility += 1 }
        func openScreenRecordingSettings() { openedScreenRecording += 1 }
    }

    func testInitialRefreshReflectsPermissionStates() {
        let fake = FakePermissions()
        fake.accessibility = true
        fake.screenRecording = false
        let vm = SettingsViewModel(permissions: fake)
        XCTAssertTrue(vm.accessibilityGranted)
        XCTAssertFalse(vm.screenRecordingGranted)
    }

    func testRequestAccessibilityRequestsAndRefreshes() {
        let fake = FakePermissions()
        let vm = SettingsViewModel(permissions: fake)
        XCTAssertFalse(vm.accessibilityGranted)

        vm.requestAccessibility()

        XCTAssertEqual(fake.requestedAccessibility, 1)
        XCTAssertTrue(vm.accessibilityGranted, "refresh() should pick up the just-granted state")
    }

    func testRequestScreenRecordingRequestsAndRefreshes() {
        let fake = FakePermissions()
        let vm = SettingsViewModel(permissions: fake)

        vm.requestScreenRecording()

        XCTAssertEqual(fake.requestedScreenRecording, 1)
        XCTAssertTrue(vm.screenRecordingGranted)
    }

    func testOpenSettingsDelegatesToPermissions() {
        let fake = FakePermissions()
        let vm = SettingsViewModel(permissions: fake)

        vm.openAccessibilitySettings()
        vm.openScreenRecordingSettings()

        XCTAssertEqual(fake.openedAccessibility, 1)
        XCTAssertEqual(fake.openedScreenRecording, 1)
    }

    func testVerboseLoggingTogglesTheLogFlag() {
        let original = Log.isVerbose
        defer { Log.isVerbose = original }

        let vm = SettingsViewModel(permissions: FakePermissions())
        vm.verboseLogging = true
        XCTAssertTrue(Log.isVerbose)
        vm.verboseLogging = false
        XCTAssertFalse(Log.isVerbose)
    }
}
