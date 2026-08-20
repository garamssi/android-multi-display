import XCTest
@testable import DeskLink

/// The Mac's pairing PIN is a persisted 6-digit numeric string; the tablet enters it to
/// derive the same key. These tests guard the format and persistence, restoring any real
/// stored value afterward.
final class PairingPinTests: XCTestCase {

    func testCurrentPinIsSixNumericDigitsAndStable() {
        let saved = UserDefaults.standard.string(forKey: PairingPin.defaultsKey)
        let savedAt = UserDefaults.standard.object(forKey: PairingPin.generatedAtKey)
        defer { restore(saved, savedAt) }
        UserDefaults.standard.removeObject(forKey: PairingPin.defaultsKey)

        let pin = PairingPin.current
        XCTAssertEqual(pin.count, ProtocolConstants.pairingPinLength)
        XCTAssertTrue(pin.allSatisfy(\.isNumber))
        // Stable across reads (persisted, not regenerated each time).
        XCTAssertEqual(pin, PairingPin.current)
    }

    func testRegeneratePersistsAValidPin() {
        let saved = UserDefaults.standard.string(forKey: PairingPin.defaultsKey)
        let savedAt = UserDefaults.standard.object(forKey: PairingPin.generatedAtKey)
        defer { restore(saved, savedAt) }

        let regenerated = PairingPin.regenerate()
        XCTAssertEqual(regenerated.count, ProtocolConstants.pairingPinLength)
        XCTAssertTrue(regenerated.allSatisfy(\.isNumber))
        XCTAssertEqual(regenerated, PairingPin.current) // persisted
    }

    func testRegenerateStampsBirthTimeAndExpiry() {
        let saved = UserDefaults.standard.string(forKey: PairingPin.defaultsKey)
        let savedAt = UserDefaults.standard.object(forKey: PairingPin.generatedAtKey)
        defer { restore(saved, savedAt) }

        let t0 = Date(timeIntervalSince1970: 1_000_000)
        PairingPin.regenerate(now: t0)

        XCTAssertEqual(PairingPin.generatedAt, t0)
        XCTAssertFalse(PairingPin.isExpired(now: t0))
        XCTAssertFalse(PairingPin.isExpired(now: t0.addingTimeInterval(PairingPin.lifetime - 1)))
        XCTAssertTrue(PairingPin.isExpired(now: t0.addingTimeInterval(PairingPin.lifetime)))
    }

    func testSecondsRemainingCountsDownToZero() {
        let saved = UserDefaults.standard.string(forKey: PairingPin.defaultsKey)
        let savedAt = UserDefaults.standard.object(forKey: PairingPin.generatedAtKey)
        defer { restore(saved, savedAt) }

        let t0 = Date(timeIntervalSince1970: 1_000_000)
        PairingPin.regenerate(now: t0)

        XCTAssertEqual(PairingPin.secondsRemaining(now: t0), Int(PairingPin.lifetime))
        XCTAssertEqual(PairingPin.secondsRemaining(now: t0.addingTimeInterval(45)), 15)
        XCTAssertEqual(PairingPin.secondsRemaining(now: t0.addingTimeInterval(PairingPin.lifetime)), 0)
        XCTAssertEqual(PairingPin.secondsRemaining(now: t0.addingTimeInterval(999)), 0)
    }

    func testRotateIfExpiredRotatesOnlyAfterLifetime() {
        let saved = UserDefaults.standard.string(forKey: PairingPin.defaultsKey)
        let savedAt = UserDefaults.standard.object(forKey: PairingPin.generatedAtKey)
        defer { restore(saved, savedAt) }

        let t0 = Date(timeIntervalSince1970: 1_000_000)
        let first = PairingPin.regenerate(now: t0)

        // Still within the window: unchanged PIN, birth time untouched.
        XCTAssertEqual(PairingPin.rotateIfExpired(now: t0.addingTimeInterval(30)), first)
        XCTAssertEqual(PairingPin.generatedAt, t0)

        // Past the window: rotates and re-stamps to the new `now` (asserted on the
        // deterministic timestamp; two random PINs could coincide).
        let t1 = t0.addingTimeInterval(PairingPin.lifetime + 5)
        _ = PairingPin.rotateIfExpired(now: t1)
        XCTAssertEqual(PairingPin.generatedAt, t1)
        XCTAssertFalse(PairingPin.isExpired(now: t1))
    }

    private func restore(_ value: String?, _ generatedAt: Any? = nil) {
        if let value {
            UserDefaults.standard.set(value, forKey: PairingPin.defaultsKey)
        } else {
            UserDefaults.standard.removeObject(forKey: PairingPin.defaultsKey)
        }
        if let generatedAt {
            UserDefaults.standard.set(generatedAt, forKey: PairingPin.generatedAtKey)
        } else {
            UserDefaults.standard.removeObject(forKey: PairingPin.generatedAtKey)
        }
    }
}
