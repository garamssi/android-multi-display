import XCTest
@testable import DeskLink

// The PIN authorises a NEW pairing. Rotating it while a client that already paired is on its
// way back revokes trust that was granted: the tablet reconnects with the key it derived from
// the old PIN, fails authentication, and the user has to type a new PIN for a session they
// never left.
final class PairingPinRotationPolicyTests: XCTestCase {

    func testRotationIsAllowedWhileWaitingForAFirstClient() {
        XCTAssertTrue(
            PairingPinRotationPolicy.mayRotate(isClientConnected: false, secondsSinceDrop: nil)
        )
    }

    func testRotationIsBlockedWhileAClientIsConnected() {
        XCTAssertFalse(
            PairingPinRotationPolicy.mayRotate(isClientConnected: true, secondsSinceDrop: nil)
        )
    }

    // A display-mode switch drops the session for well under a second, and it happens with
    // Settings open by construction -- that is where the switch is -- so the one-second
    // pairing tick lands inside the gap.
    func testRotationIsBlockedRightAfterASessionDrops() {
        XCTAssertFalse(
            PairingPinRotationPolicy.mayRotate(isClientConnected: false, secondsSinceDrop: 0.5)
        )
    }

    func testRotationIsBlockedForTheWholeClientReconnectWindow() {
        let window = ProtocolConstants.clientReconnectWindowSeconds
        XCTAssertFalse(
            PairingPinRotationPolicy.mayRotate(isClientConnected: false, secondsSinceDrop: window - 0.1)
        )
    }

    // Past the window the client has given up rather than returning, and holding the PIN
    // would keep a stale one usable indefinitely -- which is what rotation is for.
    func testRotationResumesOnceTheClientHasGivenUp() {
        let hold = PairingPinRotationPolicy.holdAfterDropSeconds
        XCTAssertTrue(
            PairingPinRotationPolicy.mayRotate(isClientConnected: false, secondsSinceDrop: hold + 0.1)
        )
    }

    func testTheHoldCoversTheClientReconnectWindow() {
        XCTAssertGreaterThan(
            PairingPinRotationPolicy.holdAfterDropSeconds,
            ProtocolConstants.clientReconnectWindowSeconds,
            "a hold shorter than the window rotates the PIN while the client is still trying"
        )
    }
}
