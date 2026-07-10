package com.desklink.android.data

import com.desklink.android.data.security.CertPinStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The pure trust-on-first-use pin logic behind TLS pinning: fingerprints are stable
 * lowercase SHA-256 hex, and pins are stored/read back per host.
 */
class CertPinStoreTest {

    @Test
    fun `fingerprint is a stable lowercase sha-256 hex`() {
        val der = byteArrayOf(1, 2, 3, 4, 5)
        val fingerprint = CertPinStore.fingerprint(der)

        assertEquals(64, fingerprint.length)
        assertEquals(fingerprint, CertPinStore.fingerprint(der))
        assertEquals(fingerprint.lowercase(), fingerprint)
    }

    @Test
    fun `pins are absent first, then stored per host (TOFU)`() {
        val store = CertPinStore(FakeSettingsStore())

        assertNull(store.pinFor("192.168.0.5"))
        store.setPin("192.168.0.5", "deadbeef")
        assertEquals("deadbeef", store.pinFor("192.168.0.5"))
        // A different host has its own (still absent) pin.
        assertNull(store.pinFor("10.0.0.9"))
    }
}
