import XCTest
@testable import DeskLink

final class TransportSettingsTests: XCTestCase {

    func testWifiDisabledByDefault() {
        // Remove any persisted value so we observe the genuine default (false).
        let original = TransportSettings.wifiEnabled
        defer { TransportSettings.wifiEnabled = original }

        UserDefaults.standard.removeObject(forKey: TransportSettings.wifiEnabledDefaultsKey)
        XCTAssertFalse(TransportSettings.wifiEnabled, "USB-only must be the default; LAN is opt-in")
    }

    func testWifiEnabledPersistsThroughUserDefaults() {
        let original = TransportSettings.wifiEnabled
        defer { TransportSettings.wifiEnabled = original }

        TransportSettings.wifiEnabled = true
        XCTAssertTrue(TransportSettings.wifiEnabled)
        XCTAssertTrue(UserDefaults.standard.bool(forKey: TransportSettings.wifiEnabledDefaultsKey))

        TransportSettings.wifiEnabled = false
        XCTAssertFalse(TransportSettings.wifiEnabled)
    }
}
