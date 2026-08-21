import XCTest
@testable import DeskLink

// Wi-Fi without a TLS identity used to start a plaintext LAN listener. No client can use it
// -- the tablet requires TLS on LAN -- and if one did, the pairing PIN would travel in the
// clear. So the listener was both useless and unsafe, and the only sign was one log line.
final class LanAvailabilityTests: XCTestCase {

    func testWifiOffMeansNoLanListener() {
        XCTAssertEqual(
            LanAvailability.decide(wifiEnabled: false, hasTlsIdentity: true),
            .disabledByPreference
        )
    }

    func testWifiOnWithAnIdentityEnablesLan() {
        XCTAssertEqual(
            LanAvailability.decide(wifiEnabled: true, hasTlsIdentity: true),
            .enabled
        )
    }

    func testWifiOnWithoutAnIdentityIsBlockedRatherThanPlaintext() {
        XCTAssertEqual(
            LanAvailability.decide(wifiEnabled: true, hasTlsIdentity: false),
            .blockedWithoutTlsIdentity
        )
    }

    // The preference is off, so the missing identity is not what is stopping anything; saying
    // so would send the user to fix something that is not the problem.
    func testWifiOffIsReportedAsThePreferenceEvenWithoutAnIdentity() {
        XCTAssertEqual(
            LanAvailability.decide(wifiEnabled: false, hasTlsIdentity: false),
            .disabledByPreference
        )
    }

    func testOnlyTheEnabledCaseBindsAListener() {
        XCTAssertTrue(LanAvailability.enabled.bindsListener)
        XCTAssertFalse(LanAvailability.disabledByPreference.bindsListener)
        XCTAssertFalse(LanAvailability.blockedWithoutTlsIdentity.bindsListener)
    }
}
