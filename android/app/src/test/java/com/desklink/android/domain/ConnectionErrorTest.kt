package com.desklink.android.domain

import com.desklink.android.domain.model.ConnectionError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConnectionErrorTest {

    @Test
    fun `fromCode returns correct error`() {
        assertEquals(ConnectionError.REFUSED, ConnectionError.fromCode(1000))
        assertEquals(ConnectionError.DECODER_INIT_FAILED, ConnectionError.fromCode(1102))
    }

    @Test
    fun `fromCode returns null for unknown code`() {
        assertNull(ConnectionError.fromCode(9999))
    }

    @Test
    fun `error codes follow category ranges`() {
        ConnectionError.entries.forEach { error ->
            when {
                error.code in 1000..1099 -> {} // connection
                error.code in 1100..1199 -> {} // codec
                error.code in 1200..1299 -> {} // display
                error.code in 1300..1399 -> {} // input
                error.code in 1400..1499 -> {} // config
                else -> throw AssertionError("Error ${error.name} code ${error.code} outside known ranges")
            }
        }
    }
}
