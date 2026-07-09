package com.desklink.android.data

import com.desklink.android.data.security.PairingAuth
import com.desklink.android.data.security.PairingCrypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the mutual-auth proofs to the cross-platform golden vectors (tools/protocol_vectors.py,
 * AUTH_*). The macOS PairingAuthTests assert the identical values, so a proof mismatch on
 * either side (which would fail the LAN handshake) fails a unit test.
 */
class PairingAuthTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private val key = PairingCrypto.derivePsk("123456")
    private val serverNonce = ByteArray(16) { it.toByte() }            // 00..0F
    private val clientNonce = ByteArray(16) { (it + 16).toByte() }     // 10..1F

    @Test
    fun `client proof matches the golden vector`() {
        assertEquals(
            "625675e556c49c7c3d7696fc998af1a1b08e566770ade8535bce854f70a197c7",
            PairingAuth.clientProof(key, serverNonce, clientNonce).hex(),
        )
    }

    @Test
    fun `server proof matches the golden vector`() {
        assertEquals(
            "021def164dbf188f2c926fd01ee7063c26dd682113f7a561d027cee5d34c38e8",
            PairingAuth.serverProof(key, serverNonce, clientNonce).hex(),
        )
    }

    @Test
    fun `verify accepts a correct proof and rejects the wrong-direction proof`() {
        val client = PairingAuth.clientProof(key, serverNonce, clientNonce)
        assertTrue(PairingAuth.verify(client, PairingAuth.clientProof(key, serverNonce, clientNonce)))
        assertFalse(PairingAuth.verify(client, PairingAuth.serverProof(key, serverNonce, clientNonce)))
    }

    @Test
    fun `a different PIN produces a different proof`() {
        val wrongKey = PairingCrypto.derivePsk("000000")
        assertFalse(
            PairingAuth.clientProof(key, serverNonce, clientNonce)
                .contentEquals(PairingAuth.clientProof(wrongKey, serverNonce, clientNonce)),
        )
    }
}
