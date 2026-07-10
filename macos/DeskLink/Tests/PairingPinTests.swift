import XCTest
@testable import DeskLink

/// The Mac's pairing PIN is a persisted 6-digit numeric string; the tablet enters it to
/// derive the same key. These tests guard the format and persistence, restoring any real
/// stored value afterward.
final class PairingPinTests: XCTestCase {

    func testCurrentPinIsSixNumericDigitsAndStable() {
        let saved = UserDefaults.standard.string(forKey: PairingPin.defaultsKey)
        defer { restore(saved) }
        UserDefaults.standard.removeObject(forKey: PairingPin.defaultsKey)

        let pin = PairingPin.current
        XCTAssertEqual(pin.count, ProtocolConstants.pairingPinLength)
        XCTAssertTrue(pin.allSatisfy(\.isNumber))
        // Stable across reads (persisted, not regenerated each time).
        XCTAssertEqual(pin, PairingPin.current)
    }

    func testRegeneratePersistsAValidPin() {
        let saved = UserDefaults.standard.string(forKey: PairingPin.defaultsKey)
        defer { restore(saved) }

        let regenerated = PairingPin.regenerate()
        XCTAssertEqual(regenerated.count, ProtocolConstants.pairingPinLength)
        XCTAssertTrue(regenerated.allSatisfy(\.isNumber))
        XCTAssertEqual(regenerated, PairingPin.current) // persisted
    }

    private func restore(_ value: String?) {
        if let value {
            UserDefaults.standard.set(value, forKey: PairingPin.defaultsKey)
        } else {
            UserDefaults.standard.removeObject(forKey: PairingPin.defaultsKey)
        }
    }
}
