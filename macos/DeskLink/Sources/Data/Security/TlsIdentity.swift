import Foundation
import Network
import Security

/// Loads the self-signed TLS server identity (created by `scripts/create_tls_cert.sh`)
/// from the login keychain, used to serve the LAN (Wi-Fi) channels over TLS. USB
/// (loopback) does not use it. Returns nil if the identity is absent — the caller then
/// keeps the LAN listener plaintext and logs a hint to run the setup script.
enum TlsIdentity {

    /// The certificate common name the setup script assigns.
    static let commonName = "DeskLink TLS Server"

    /// The Network.framework identity for the LAN TLS listener, if present.
    static func loadSecIdentity() -> sec_identity_t? {
        guard let identity = loadKeychainIdentity() else { return nil }
        return sec_identity_create(identity)
    }

    /// The keychain `SecIdentity` whose certificate common name matches [commonName].
    private static func loadKeychainIdentity() -> SecIdentity? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnRef as String: true,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let identities = result as? [SecIdentity] else {
            return nil
        }
        // Match by certificate CN rather than the keychain label, which varies by how
        // the identity was imported.
        return identities.first { identity in
            certificateCommonName(of: identity) == commonName
        }
    }

    private static func certificateCommonName(of identity: SecIdentity) -> String? {
        var certificate: SecCertificate?
        guard SecIdentityCopyCertificate(identity, &certificate) == errSecSuccess,
              let certificate else {
            return nil
        }
        var commonName: CFString?
        guard SecCertificateCopyCommonName(certificate, &commonName) == errSecSuccess else {
            return nil
        }
        return commonName as String?
    }
}
