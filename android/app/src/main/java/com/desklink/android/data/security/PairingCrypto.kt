package com.desklink.android.data.security

import com.desklink.android.domain.model.ProtocolConstants
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// HKDF-SHA256 (RFC 5869) PSK derivation. MUST match the macOS server byte-for-byte or the TLS-PSK handshake fails. Golden vectors: tools/pairing_vectors.py.
object PairingCrypto {

    fun derivePsk(pin: String): ByteArray {
        val salt = ProtocolConstants.PSK_HKDF_SALT.toByteArray(Charsets.UTF_8)
        val info = ProtocolConstants.PSK_HKDF_INFO.toByteArray(Charsets.UTF_8)
        val ikm = pin.toByteArray(Charsets.UTF_8)

        val prk = hmacSha256(key = salt, data = ikm)
        return hkdfExpand(prk, info, ProtocolConstants.PSK_LENGTH_BYTES)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val output = ByteArray(length)
        var block = ByteArray(0)
        var generated = 0
        var counter = 1
        while (generated < length) {
            block = hmacSha256(key = prk, data = block + info + byteArrayOf(counter.toByte()))
            val take = minOf(block.size, length - generated)
            block.copyInto(output, destinationOffset = generated, startIndex = 0, endIndex = take)
            generated += take
            counter++
        }
        return output
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
