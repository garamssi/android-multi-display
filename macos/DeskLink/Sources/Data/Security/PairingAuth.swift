import CryptoKit
import Foundation

/// LAN mutual authentication over the (TLS) control channel: both sides prove they know
/// the pairing key K = HKDF(PIN) (see `PairingCrypto`) via an HMAC-SHA256
/// challenge-response, without ever sending the PIN or key.
///
/// proof = HMAC-SHA256(K, context || serverNonce || clientNonce). The two contexts
/// (`ProtocolConstants.authClientContext` / `.authServerContext`) bind each proof to its
/// direction so a proof cannot be replayed the other way. The cross-platform contract
/// and golden vectors live in tools/protocol_vectors.py (AUTH_*).
enum PairingAuth {

    /// The client's proof, sent in AUTH_RESPONSE after its nonce.
    static func clientProof(key: Data, serverNonce: Data, clientNonce: Data) -> Data {
        proof(context: ProtocolConstants.authClientContext, key: key, serverNonce: serverNonce, clientNonce: clientNonce)
    }

    /// The server's proof, sent in AUTH_CONFIRM once the client's proof verifies.
    static func serverProof(key: Data, serverNonce: Data, clientNonce: Data) -> Data {
        proof(context: ProtocolConstants.authServerContext, key: key, serverNonce: serverNonce, clientNonce: clientNonce)
    }

    /// Constant-time comparison of a received proof against the expected one.
    static func verify(_ received: Data, expected: Data) -> Bool {
        guard received.count == expected.count else { return false }
        var difference: UInt8 = 0
        for (a, b) in zip(received, expected) { difference |= a ^ b }
        return difference == 0
    }

    private static func proof(context: String, key: Data, serverNonce: Data, clientNonce: Data) -> Data {
        var message = Data(context.utf8)
        message.append(serverNonce)
        message.append(clientNonce)
        let code = HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: key))
        return Data(code)
    }
}
