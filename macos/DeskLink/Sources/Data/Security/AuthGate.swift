import Foundation

/// Server-side LAN pairing authentication state for a control-channel session.
///
/// Actor-isolated because two concurrent tasks touch it: the keep-alive loop issues the
/// challenge when a client connects, and the receive loop verifies the client's response.
///
/// `key == nil` means authentication is not required (USB / loopback, trusted by the
/// physical link) — the gate reports authenticated immediately and issues no challenge.
actor AuthGate {

    /// Source of the current pairing key, read afresh at each [beginChallenge] so a
    /// rotating key (the displayed PIN changes over time) is honored per connection.
    /// Returning nil means auth is not required (USB / loopback).
    private let keyProvider: @Sendable () -> Data?
    /// The key snapshotted when the current client's challenge was issued, so a rotation
    /// between challenge and response can't invalidate an in-flight exchange.
    private var sessionKey: Data?
    private var serverNonce: Data?
    private var authenticated: Bool
    private var failures = 0

    init(keyProvider: @escaping @Sendable () -> Data?) {
        self.keyProvider = keyProvider
        self.authenticated = (keyProvider() == nil)
    }

    /// Fixed-key initializer for a constant key (USB's nil, or tests). Kept as its own
    /// designated init so it doesn't depend on actor init-delegation rules.
    init(key: Data?) {
        self.keyProvider = { key }
        self.authenticated = (key == nil)
    }

    var isAuthenticated: Bool { authenticated }

    /// A fresh AUTH_CHALLENGE payload (server nonce) for a connecting client, or nil if
    /// auth isn't required or the failure lockout is in effect. Snapshots the current key
    /// and resets per-client state.
    func beginChallenge() -> Data? {
        guard let key = keyProvider(), failures < Self.maxFailures else { return nil }
        sessionKey = key
        authenticated = false
        let nonce = Data((0 ..< ProtocolConstants.authNonceLength).map { _ in UInt8.random(in: 0 ... 255) })
        serverNonce = nonce
        return nonce
    }

    /// Verifies an AUTH_RESPONSE (clientNonce || clientProof). On success marks the
    /// session authenticated and returns the AUTH_CONFIRM payload (server proof); on
    /// failure returns nil and counts toward the lockout.
    func verifyResponse(_ payload: Data) -> Data? {
        guard let key = sessionKey, let serverNonce,
              payload.count == ProtocolConstants.authNonceLength + Self.proofLength else {
            failures += 1
            return nil
        }
        let clientNonce = Data(payload.prefix(ProtocolConstants.authNonceLength))
        let clientProof = Data(payload.dropFirst(ProtocolConstants.authNonceLength))
        let expected = PairingAuth.clientProof(key: key, serverNonce: serverNonce, clientNonce: clientNonce)
        guard PairingAuth.verify(clientProof, expected: expected) else {
            failures += 1
            return nil
        }
        authenticated = true
        return PairingAuth.serverProof(key: key, serverNonce: serverNonce, clientNonce: clientNonce)
    }

    private static let proofLength = 32
    static let maxFailures = 5
}
