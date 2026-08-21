import Foundation

actor AuthGate {

    /// What a client should be told when it connects.
    ///
    /// `beginChallenge` used to answer nil for both "no pairing needed" and "too many
    /// failures", so the caller could not tell them apart and said nothing in either case --
    /// which the client reads as a wrong PIN forever.
    enum ChallengeOutcome: Equatable {
        case notRequired
        case challenge(Data)
        case lockedOut(retryAfterSeconds: Int)
    }

    // Read afresh per beginChallenge so a rotating PIN is honored; nil means auth not required.
    private let keyProvider: @Sendable () -> Data?
    private let now: @Sendable () -> Date
    // Snapshot of the key at challenge time so a rotation mid-exchange can't invalidate an in-flight auth.
    private var sessionKey: Data?
    private var serverNonce: Data?
    private var authenticated: Bool
    private var failures = 0
    private var lockedUntil: Date?

    init(keyProvider: @escaping @Sendable () -> Data?, now: @escaping @Sendable () -> Date = Date.init) {
        self.keyProvider = keyProvider
        self.now = now
        self.authenticated = (keyProvider() == nil)
    }

    init(key: Data?, now: @escaping @Sendable () -> Date = Date.init) {
        self.keyProvider = { key }
        self.now = now
        self.authenticated = (key == nil)
    }

    var isAuthenticated: Bool { authenticated }

    func beginChallenge() -> ChallengeOutcome {
        guard let key = keyProvider() else { return .notRequired }

        if let lockedUntil {
            let remaining = lockedUntil.timeIntervalSince(now())
            guard remaining <= 0 else {
                return .lockedOut(retryAfterSeconds: Int(remaining.rounded(.up)))
            }
            // The wait was served. A fresh budget, so one later slip does not re-lock
            // immediately -- otherwise a single mistype puts the user straight back in.
            self.lockedUntil = nil
            failures = 0
        }

        sessionKey = key
        authenticated = false
        let nonce = Data((0 ..< ProtocolConstants.authNonceLength).map { _ in UInt8.random(in: 0 ... 255) })
        serverNonce = nonce
        return .challenge(nonce)
    }

    func verifyResponse(_ payload: Data) -> Data? {
        guard let key = sessionKey, let serverNonce,
              payload.count == ProtocolConstants.authNonceLength + Self.proofLength else {
            recordFailure()
            return nil
        }
        let clientNonce = Data(payload.prefix(ProtocolConstants.authNonceLength))
        let clientProof = Data(payload.dropFirst(ProtocolConstants.authNonceLength))
        let expected = PairingAuth.clientProof(key: key, serverNonce: serverNonce, clientNonce: clientNonce)
        guard PairingAuth.verify(clientProof, expected: expected) else {
            recordFailure()
            return nil
        }
        authenticated = true
        return PairingAuth.serverProof(key: key, serverNonce: serverNonce, clientNonce: clientNonce)
    }

    private func recordFailure() {
        failures += 1
        if failures >= Self.maxFailures {
            lockedUntil = now().addingTimeInterval(Self.lockoutSeconds)
        }
    }

    private static let proofLength = 32

    /// Wrong proofs allowed before the gate makes the client wait.
    static let maxFailures = 5

    /// How long that wait is.
    ///
    /// Bounded rather than permanent: refusing for the rest of the session looks exactly like
    /// a wrong PIN from the tablet, and the only way out was restarting sharing on the Mac.
    /// Long enough that guessing a six-digit PIN is hopeless -- 5 tries per half minute
    /// against a million values -- and short enough to be a wait rather than a dead end.
    static let lockoutSeconds: TimeInterval = 30
}
