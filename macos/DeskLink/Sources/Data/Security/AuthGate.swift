import Foundation

/// Server-side LAN pairing authentication state for a control-channel session.
///
/// Actor-isolated because two concurrent tasks touch it: the keep-alive loop issues the
/// challenge when a client connects, and the receive loop verifies the client's response.
///
/// `key == nil` means authentication is not required (USB / loopback, trusted by the
/// physical link) — the gate reports authenticated immediately and issues no challenge.
actor AuthGate {

    private let key: Data?
    private var serverNonce: Data?
    private var authenticated: Bool
    private var failures = 0

    init(key: Data?) {
        self.key = key
        self.authenticated = (key == nil)
    }

    var isAuthenticated: Bool { authenticated }

    /// A fresh AUTH_CHALLENGE payload (server nonce) for a connecting client, or nil if
    /// auth isn't required or the failure lockout is in effect. Resets per-client state.
    func beginChallenge() -> Data? {
        guard key != nil, failures < Self.maxFailures else { return nil }
        authenticated = false
        let nonce = Data((0 ..< ProtocolConstants.authNonceLength).map { _ in UInt8.random(in: 0 ... 255) })
        serverNonce = nonce
        return nonce
    }

    /// Verifies an AUTH_RESPONSE (clientNonce || clientProof). On success marks the
    /// session authenticated and returns the AUTH_CONFIRM payload (server proof); on
    /// failure returns nil and counts toward the lockout.
    func verifyResponse(_ payload: Data) -> Data? {
        guard let key, let serverNonce,
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
