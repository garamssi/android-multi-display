import Foundation

/// The pairing PIN the Mac displays so a tablet can authenticate the LAN (Wi-Fi)
/// connection. Persisted in `UserDefaults` with the time it was generated; both sides
/// derive the same key from it (see `PairingCrypto`), and the PIN itself is never sent
/// over the wire.
///
/// A displayed PIN is short-lived: if it isn't used to pair within [lifetime], it should
/// rotate to a fresh code so a value left on screen doesn't stay valid indefinitely. The
/// age is tracked by wall-clock (the generation timestamp), so it keeps elapsing whether
/// or not anything is watching; the actual rotation is driven by the caller (the Settings
/// view, while it is showing the PIN and no device is connected) via [rotateIfExpired].
enum PairingPin {

    static let defaultsKey = "pairingPin"
    static let generatedAtKey = "pairingPinGeneratedAt"

    /// How long a freshly generated PIN stays valid before it rotates when unused.
    static let lifetime: TimeInterval = 60

    /// The current PIN, generating and persisting one (with a timestamp) on first access.
    static var current: String {
        if let existing = UserDefaults.standard.string(forKey: defaultsKey),
           existing.count == ProtocolConstants.pairingPinLength,
           existing.allSatisfy(\.isNumber) {
            return existing
        }
        return regenerate()
    }

    /// When the current PIN was generated, or nil if unknown — e.g. a PIN persisted by an
    /// older build that stored no timestamp, which is then treated as already expired so
    /// it rotates on the first check.
    static var generatedAt: Date? {
        let stored = UserDefaults.standard.double(forKey: generatedAtKey)
        return stored > 0 ? Date(timeIntervalSince1970: stored) : nil
    }

    /// Generates, persists, and returns a fresh PIN, stamping [now] as its birth time
    /// (invalidates any prior pairing).
    @discardableResult
    static func regenerate(now: Date = Date()) -> String {
        // SystemRandomNumberGenerator is cryptographically secure on Apple platforms.
        let pin = (0 ..< ProtocolConstants.pairingPinLength)
            .map { _ in String(Int.random(in: 0 ... 9)) }
            .joined()
        UserDefaults.standard.set(pin, forKey: defaultsKey)
        UserDefaults.standard.set(now.timeIntervalSince1970, forKey: generatedAtKey)
        return pin
    }

    /// True once the current PIN has outlived [lifetime] (or has no known birth time).
    static func isExpired(now: Date = Date()) -> Bool {
        guard let generatedAt else { return true }
        return now.timeIntervalSince(generatedAt) >= lifetime
    }

    /// Whole seconds until the current PIN rotates (0 when already expired / unknown age).
    static func secondsRemaining(now: Date = Date()) -> Int {
        guard let generatedAt else { return 0 }
        let remaining = lifetime - now.timeIntervalSince(generatedAt)
        return max(0, Int(remaining.rounded(.up)))
    }

    /// Rotates the PIN if it has expired; returns the current (possibly fresh) PIN. This is
    /// the single entry point the UI ticks so an unused code rolls over on schedule.
    @discardableResult
    static func rotateIfExpired(now: Date = Date()) -> String {
        if isExpired(now: now) { return regenerate(now: now) }
        return current
    }
}
