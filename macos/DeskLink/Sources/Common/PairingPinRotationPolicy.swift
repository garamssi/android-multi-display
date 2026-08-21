import Foundation

/// Whether the pairing PIN may rotate right now.
///
/// The PIN authorises a NEW pairing. Rotating it while a client that already paired is on its
/// way back revokes trust that was already granted: the tablet reconnects with the key it
/// derived from the old PIN, fails authentication, and the user has to type a new PIN for a
/// session they never left.
///
/// A display-mode switch is exactly that case, and it is the worst one: the session drops for
/// under a second, and the switch happens with Settings open by construction, since that is
/// where the switch lives -- so the one-second pairing tick lands inside the gap.
enum PairingPinRotationPolicy {

    /// How long a dropped session is treated as "a client is coming back".
    ///
    /// Covers the client's whole reconnect window plus the server's own teardown and
    /// handshake. Past it the client has given up rather than returning, and holding the PIN
    /// any longer would keep a stale one usable -- which is what rotation exists to prevent.
    static let holdAfterDropSeconds: TimeInterval = ProtocolConstants.clientReconnectWindowSeconds + 3

    static func mayRotate(isClientConnected: Bool, secondsSinceDrop: TimeInterval?) -> Bool {
        guard !isClientConnected else { return false }
        guard let secondsSinceDrop else { return true }
        return secondsSinceDrop > holdAfterDropSeconds
    }
}
