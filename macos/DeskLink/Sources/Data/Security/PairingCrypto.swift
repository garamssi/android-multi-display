import CryptoKit
import Foundation

// Must match the Android client byte-for-byte or the TLS-PSK handshake fails; golden vectors in tools/pairing_vectors.py.
enum PairingCrypto {

    static func derivePSK(pin: String) -> Data {
        let inputKeyMaterial = SymmetricKey(data: Data(pin.utf8))
        // CryptoKit deriveKey performs the full HKDF (extract + expand), matching the Android reference.
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: inputKeyMaterial,
            salt: Data(ProtocolConstants.pskHkdfSalt.utf8),
            info: Data(ProtocolConstants.pskHkdfInfo.utf8),
            outputByteCount: ProtocolConstants.pskLengthBytes
        )
        return key.withUnsafeBytes { Data($0) }
    }
}
