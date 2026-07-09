package com.desklink.android.data

import com.desklink.android.data.security.PairingCrypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the PIN -> PSK derivation to the cross-platform golden vectors (RFC 5869
 * HKDF-SHA256). These exact hex values are produced by tools/pairing_vectors.py and
 * asserted identically by the macOS PairingCryptoTests, so a divergence on either
 * platform (which would break the TLS-PSK handshake) fails a unit test.
 */
class PairingCryptoTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `derives the golden PSK from the PIN`() {
        assertEquals(
            "97a17f725a8dbce5993a82f3d43ca7cd569acb9756ca2e656607726e110e83b8",
            PairingCrypto.derivePsk("123456").hex(),
        )
        assertEquals(
            "bc4f4adccff971132b24c0dcdebaec75574683fe9fa84471533d9f88ff492016",
            PairingCrypto.derivePsk("000000").hex(),
        )
        assertEquals(
            "9893e3e98b4de2e22c4aced3cfce08827e28461827f405d2dbaafe8559660d79",
            PairingCrypto.derivePsk("987654").hex(),
        )
    }

    @Test
    fun `derives a 32-byte key`() {
        assertEquals(32, PairingCrypto.derivePsk("123456").size)
    }

    @Test
    fun `different PINs derive different keys`() {
        org.junit.jupiter.api.Assertions.assertFalse(
            PairingCrypto.derivePsk("123456").contentEquals(PairingCrypto.derivePsk("123457")),
        )
    }
}
