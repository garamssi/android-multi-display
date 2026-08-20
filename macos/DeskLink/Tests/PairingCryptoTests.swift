import XCTest
@testable import DeskLink

/// Pins the PIN -> PSK derivation to the cross-platform golden vectors (RFC 5869
/// HKDF-SHA256). These exact values are produced by tools/pairing_vectors.py and
/// asserted identically by the Android PairingCryptoTest, so a divergence on either
/// platform (which would break the TLS-PSK handshake) fails a unit test. Compared as
/// raw bytes via `Data(hex:)` (from TestSupport) to avoid any hex-case ambiguity.
final class PairingCryptoTests: XCTestCase {

    func testDerivesGoldenPSKFromPIN() {
        XCTAssertEqual(
            PairingCrypto.derivePSK(pin: "123456"),
            Data(hex: "97a17f725a8dbce5993a82f3d43ca7cd569acb9756ca2e656607726e110e83b8")
        )
        XCTAssertEqual(
            PairingCrypto.derivePSK(pin: "000000"),
            Data(hex: "bc4f4adccff971132b24c0dcdebaec75574683fe9fa84471533d9f88ff492016")
        )
        XCTAssertEqual(
            PairingCrypto.derivePSK(pin: "987654"),
            Data(hex: "9893e3e98b4de2e22c4aced3cfce08827e28461827f405d2dbaafe8559660d79")
        )
    }

    func testDerivesThirtyTwoByteKey() {
        XCTAssertEqual(PairingCrypto.derivePSK(pin: "123456").count, 32)
    }

    func testDifferentPINsDeriveDifferentKeys() {
        XCTAssertNotEqual(
            PairingCrypto.derivePSK(pin: "123456"),
            PairingCrypto.derivePSK(pin: "123457")
        )
    }
}
