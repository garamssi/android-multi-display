import CryptoKit
import Foundation

// Distinct client/server contexts bind each proof to its direction (no cross-replay); must match Android byte-for-byte, golden vectors in tools/protocol_vectors.py.
enum PairingAuth {

    static func clientProof(key: Data, serverNonce: Data, clientNonce: Data) -> Data {
        proof(context: ProtocolConstants.authClientContext, key: key, serverNonce: serverNonce, clientNonce: clientNonce)
    }

    static func serverProof(key: Data, serverNonce: Data, clientNonce: Data) -> Data {
        proof(context: ProtocolConstants.authServerContext, key: key, serverNonce: serverNonce, clientNonce: clientNonce)
    }

    // Constant-time comparison; do not replace with == (timing side-channel).
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
