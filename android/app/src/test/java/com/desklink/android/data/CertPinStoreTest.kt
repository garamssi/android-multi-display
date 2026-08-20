package com.desklink.android.data

import com.desklink.android.data.security.CertPinStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
        assertNull(store.pinFor("10.0.0.9"))
    }
}
