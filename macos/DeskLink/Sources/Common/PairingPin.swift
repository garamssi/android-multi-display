import Foundation

/// The pairing PIN the Mac displays so a tablet can authenticate the LAN (Wi-Fi)
/// connection. Generated once and persisted in `UserDefaults`; both sides derive the
/// same key from it (see `PairingCrypto`), and the PIN itself is never sent over the
/// wire. Regenerating it invalidates any prior pairing.
enum PairingPin {

    static let defaultsKey = "pairingPin"

    /// The current PIN, generating and persisting one on first access.
    static var current: String {
        if let existing = UserDefaults.standard.string(forKey: defaultsKey),
           existing.count == ProtocolConstants.pairingPinLength,
           existing.allSatisfy(\.isNumber) {
            return existing
        }
        return regenerate()
    }

    /// Generates, persists, and returns a fresh PIN (invalidates any prior pairing).
    @discardableResult
    static func regenerate() -> String {
        // SystemRandomNumberGenerator is cryptographically secure on Apple platforms.
        let pin = (0 ..< ProtocolConstants.pairingPinLength)
            .map { _ in String(Int.random(in: 0 ... 9)) }
            .joined()
        UserDefaults.standard.set(pin, forKey: defaultsKey)
        return pin
    }
}
