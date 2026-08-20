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
        let challenge = await gate.beginChallenge()
        XCTAssertNil(challenge)
    }

    func testChallengeResponseRoundTripAuthenticates() async {
        let key = PairingCrypto.derivePSK(pin: "123456")
        let gate = AuthGate(key: key)

        guard let serverNonce = await gate.beginChallenge() else {
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
}
