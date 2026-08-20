package com.desklink.android.data

import com.desklink.android.data.input.ScrollSerializer
import com.desklink.android.domain.model.ScrollEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScrollSerializerTest {

    // Must match tools/protocol_vectors.py SCROLL(0.25, -0.5) golden vector.
    @Test
    fun `serialize matches golden vector`() {
        val bytes = ScrollSerializer.serialize(ScrollEvent(0.25f, -0.5f))
        assertEquals("3E800000BF000000", bytes.toHex())
        assertEquals(ScrollEvent.SERIALIZED_SIZE, bytes.size)
    }

    @Test
    fun `round trip preserves deltas`() {
        val original = ScrollEvent(deltaX = 0.123f, deltaY = -0.987f)
        assertEquals(original, ScrollSerializer.deserialize(ScrollSerializer.serialize(original)))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
