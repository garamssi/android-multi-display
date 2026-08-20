import Foundation

actor AuthGate {

    // Read afresh per beginChallenge so a rotating PIN is honored; nil means auth not required.
    private let keyProvider: @Sendable () -> Data?
    // Snapshot of the key at challenge time so a rotation mid-exchange can't invalidate an in-flight auth.
    private var sessionKey: Data?
    private var serverNonce: Data?
    private var authenticated: Bool
    private var failures = 0

    init(keyProvider: @escaping @Sendable () -> Data?) {
        self.keyProvider = keyProvider
        self.authenticated = (keyProvider() == nil)
    }

    init(key: Data?) {
        self.keyProvider = { key }
        self.authenticated = (key == nil)
    }

    var isAuthenticated: Bool { authenticated }

    func beginChallenge() -> Data? {
        guard let key = keyProvider(), failures < Self.maxFailures else { return nil }
        sessionKey = key
        authenticated = false
        let nonce = Data((0 ..< ProtocolConstants.authNonceLength).map { _ in UInt8.random(in: 0 ... 255) })
        serverNonce = nonce
        return nonce
    }

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
