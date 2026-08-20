import XCTest
@testable import DeskLink

/// Pins the mutual-auth proofs to the cross-platform golden vectors
/// (tools/protocol_vectors.py, AUTH_*). The Android PairingAuthTest asserts the
/// identical values, so a proof mismatch on either side (which would fail the LAN
/// handshake) fails a unit test.
final class PairingAuthTests: XCTestCase {

    private let key = PairingCrypto.derivePSK(pin: "123456")
    private let serverNonce = Data((0..<16).map { UInt8($0) })     // 00..0F
    private let clientNonce = Data((16..<32).map { UInt8($0) })    // 10..1F

    func testClientProofMatchesGolden() {
        XCTAssertEqual(
            PairingAuth.clientProof(key: key, serverNonce: serverNonce, clientNonce: clientNonce),
            Data(hex: "625675e556c49c7c3d7696fc998af1a1b08e566770ade8535bce854f70a197c7")
        )
    }

    func testServerProofMatchesGolden() {
        XCTAssertEqual(
            PairingAuth.serverProof(key: key, serverNonce: serverNonce, clientNonce: clientNonce),
            Data(hex: "021def164dbf188f2c926fd01ee7063c26dd682113f7a561d027cee5d34c38e8")
        )
    }

    func testVerifyAcceptsCorrectRejectsWrongDirection() {
        let client = PairingAuth.clientProof(key: key, serverNonce: serverNonce, clientNonce: clientNonce)
        XCTAssertTrue(PairingAuth.verify(
            client,
            expected: PairingAuth.clientProof(key: key, serverNonce: serverNonce, clientNonce: clientNonce)
        ))
        XCTAssertFalse(PairingAuth.verify(
            client,
            expected: PairingAuth.serverProof(key: key, serverNonce: serverNonce, clientNonce: clientNonce)
        ))
    }

    func testDifferentPINProducesDifferentProof() {
        let wrongKey = PairingCrypto.derivePSK(pin: "000000")
        XCTAssertNotEqual(
            PairingAuth.clientProof(key: key, serverNonce: serverNonce, clientNonce: clientNonce),
            PairingAuth.clientProof(key: wrongKey, serverNonce: serverNonce, clientNonce: clientNonce)
        )
    }
}
