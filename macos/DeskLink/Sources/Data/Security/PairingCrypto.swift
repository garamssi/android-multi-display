import CryptoKit
import Foundation

/// Derives the TLS-PSK from the pairing PIN using HKDF-SHA256 (RFC 5869).
///
/// This MUST match the Android client byte-for-byte or the TLS-PSK handshake fails. The
/// shared contract (salt / info / length) lives in `ProtocolConstants`; the golden
/// vectors that both platforms are tested against live in tools/pairing_vectors.py.
enum PairingCrypto {

    /// Derives the `ProtocolConstants.pskLengthBytes`-byte PSK from a numeric PIN.
    static func derivePSK(pin: String) -> Data {
        let inputKeyMaterial = SymmetricKey(data: Data(pin.utf8))
        // CryptoKit's deriveKey performs the full HKDF (extract with `salt`, then expand
        // with `info`) — RFC 5869, matching the Android/reference implementations.
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: inputKeyMaterial,
            salt: Data(ProtocolConstants.pskHkdfSalt.utf8),
            info: Data(ProtocolConstants.pskHkdfInfo.utf8),
            outputByteCount: ProtocolConstants.pskLengthBytes
        )
        return key.withUnsafeBytes { Data($0) }
    }
}
