package com.desklink.android.data.security

import com.desklink.android.domain.model.ProtocolConstants
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * LAN mutual authentication over the (TLS) control channel: both sides prove they know
 * the pairing key K = HKDF(PIN) (see [PairingCrypto]) via an HMAC-SHA256
 * challenge-response, without ever sending the PIN or key.
 *
 * proof = HMAC-SHA256(K, context || serverNonce || clientNonce). The two contexts
 * ([ProtocolConstants.AUTH_CLIENT_CONTEXT] / [ProtocolConstants.AUTH_SERVER_CONTEXT])
 * bind each proof to its direction so a proof cannot be replayed the other way. The
 * cross-platform contract and golden vectors live in tools/protocol_vectors.py (AUTH_*).
 */
object PairingAuth {

    /** The client's proof, sent in AUTH_RESPONSE after its nonce. */
    fun clientProof(key: ByteArray, serverNonce: ByteArray, clientNonce: ByteArray): ByteArray =
        proof(ProtocolConstants.AUTH_CLIENT_CONTEXT, key, serverNonce, clientNonce)

    /** The server's proof, sent in AUTH_CONFIRM once the client's proof verifies. */
    fun serverProof(key: ByteArray, serverNonce: ByteArray, clientNonce: ByteArray): ByteArray =
        proof(ProtocolConstants.AUTH_SERVER_CONTEXT, key, serverNonce, clientNonce)

    /** Constant-time comparison of a received proof against the expected one. */
    fun verify(received: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(received, expected)

    private fun proof(
        context: String,
        key: ByteArray,
        serverNonce: ByteArray,
        clientNonce: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(context.toByteArray(Charsets.UTF_8))
        mac.update(serverNonce)
        mac.update(clientNonce)
        return mac.doFinal()
    }
}
