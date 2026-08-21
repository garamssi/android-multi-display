import XCTest
@testable import DeskLink

/// Server-side pairing gate: no key => pre-authenticated + no challenge (USB); with a key
/// it issues a challenge, accepts a correct client proof (returning the matching server
/// proof), and rejects a wrong one. Proofs are cross-checked against PairingAuth (whose
/// golden vectors pin the wire format).
final class AuthGateTests: XCTestCase {

    func testNoKeyIsPreAuthenticatedAndIssuesNoChallenge() async {
        let gate = AuthGate(key: nil)
        let authenticated = await gate.isAuthenticated
        XCTAssertTrue(authenticated)
        let outcome = await gate.beginChallenge()
        XCTAssertEqual(outcome, .notRequired)
    }

    func testChallengeResponseRoundTripAuthenticates() async {
        let key = PairingCrypto.derivePSK(pin: "123456")
        let gate = AuthGate(key: key)

        guard case .challenge(let serverNonce) = await gate.beginChallenge() else {
            return XCTFail("expected a challenge when a key is set")
        }
        XCTAssertEqual(serverNonce.count, ProtocolConstants.authNonceLength)
        let beforeResponse = await gate.isAuthenticated
        XCTAssertFalse(beforeResponse)

        let clientNonce = Data((0 ..< ProtocolConstants.authNonceLength).map { UInt8($0) })
        let clientProof = PairingAuth.clientProof(key: key, serverNonce: serverNonce, clientNonce: clientNonce)

        guard let confirm = await gate.verifyResponse(clientNonce + clientProof) else {
            return XCTFail("expected a confirm for a valid response")
        }
        XCTAssertEqual(
            confirm,
            PairingAuth.serverProof(key: key, serverNonce: serverNonce, clientNonce: clientNonce)
        )
        let afterResponse = await gate.isAuthenticated
        XCTAssertTrue(afterResponse)
    }

    func testWrongProofIsRejected() async {
        let key = PairingCrypto.derivePSK(pin: "123456")
        let gate = AuthGate(key: key)
        _ = await gate.beginChallenge()

        let clientNonce = Data(repeating: 7, count: ProtocolConstants.authNonceLength)
        let badProof = Data(repeating: 0, count: 32)
        let confirm = await gate.verifyResponse(clientNonce + badProof)

        XCTAssertNil(confirm)
        let authenticated = await gate.isAuthenticated
        XCTAssertFalse(authenticated)
    }

    // Guessing has to be limited, but a session that refuses forever is indistinguishable
    // from a wrong PIN to the person holding the tablet: every later attempt fails even with
    // the right code, and the only way out was restarting sharing on the Mac.
    func testTooManyFailuresLocksOutRatherThanRejecting() async {
        let clock = TestClock()
        let gate = AuthGate(key: Data(repeating: 1, count: 32), now: clock.now)

        for _ in 0 ..< AuthGate.maxFailures {
            _ = await gate.beginChallenge()
            _ = await gate.verifyResponse(Data())
        }

        let outcome = await gate.beginChallenge()
        guard case .lockedOut(let retryAfter) = outcome else {
            return XCTFail("expected a lockout, got \(outcome)")
        }
        XCTAssertGreaterThan(retryAfter, 0)
    }

    func testTheLockoutExpiresSoPairingCanSucceedAgain() async {
        let clock = TestClock()
        let key = Data(repeating: 1, count: 32)
        let gate = AuthGate(key: key, now: clock.now)

        for _ in 0 ..< AuthGate.maxFailures {
            _ = await gate.beginChallenge()
            _ = await gate.verifyResponse(Data())
        }
        clock.advance(by: AuthGate.lockoutSeconds + 1)

        guard case .challenge(let serverNonce) = await gate.beginChallenge() else {
            return XCTFail("the lockout should have expired")
        }

        let clientNonce = Data(repeating: 9, count: ProtocolConstants.authNonceLength)
        let proof = PairingAuth.clientProof(key: key, serverNonce: serverNonce, clientNonce: clientNonce)
        let confirm = await gate.verifyResponse(clientNonce + proof)
        XCTAssertNotNil(confirm, "the right PIN must work again once the lockout expires")
        let authenticated = await gate.isAuthenticated
        XCTAssertTrue(authenticated)
    }

    func testFailureBudgetIsSpentAgainAfterALockoutExpires() async {
        let clock = TestClock()
        let gate = AuthGate(key: Data(repeating: 1, count: 32), now: clock.now)

        for _ in 0 ..< AuthGate.maxFailures {
            _ = await gate.beginChallenge()
            _ = await gate.verifyResponse(Data())
        }
        clock.advance(by: AuthGate.lockoutSeconds + 1)

        // One wrong attempt after the lockout must not immediately re-lock: the budget is
        // fresh, or a single slip would put the user back into the wait.
        _ = await gate.beginChallenge()
        _ = await gate.verifyResponse(Data())
        guard case .challenge = await gate.beginChallenge() else {
            return XCTFail("one failure after a lockout must not lock again")
        }
    }

    func testNoKeyReportsThatAuthIsNotRequired() async {
        let gate = AuthGate(key: nil)
        guard case .notRequired = await gate.beginChallenge() else {
            return XCTFail("a gate with no key requires no auth")
        }
    }
}
