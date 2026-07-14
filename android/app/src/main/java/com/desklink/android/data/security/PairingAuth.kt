package com.desklink.android.data.security

import com.desklink.android.domain.model.ProtocolConstants
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// The client/server contexts bind each HMAC proof to its direction so a proof cannot be replayed the other way. Golden vectors: tools/protocol_vectors.py.
object PairingAuth {

    fun clientProof(key: ByteArray, serverNonce: ByteArray, clientNonce: ByteArray): ByteArray =
        proof(ProtocolConstants.AUTH_CLIENT_CONTEXT, key, serverNonce, clientNonce)

    fun serverProof(key: ByteArray, serverNonce: ByteArray, clientNonce: ByteArray): ByteArray =
        proof(ProtocolConstants.AUTH_SERVER_CONTEXT, key, serverNonce, clientNonce)

    // Constant-time comparison to avoid leaking proof bytes via timing.
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
