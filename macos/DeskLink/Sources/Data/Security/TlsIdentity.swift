import Foundation
import Network
import Security

enum TlsIdentity {

    static let commonName = "DeskLink TLS Server"

    static func loadSecIdentity() -> sec_identity_t? {
        guard let identity = loadKeychainIdentity() else { return nil }
        return sec_identity_create(identity)
    }

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
        // Match by certificate CN, not the keychain label (label varies by import method).
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
