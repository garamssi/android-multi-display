package com.desklink.android.data

import com.desklink.android.data.input.PointerButtonSerializer
import com.desklink.android.domain.model.PointerButtonEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PointerButtonSerializerTest {

    // Must match tools/protocol_vectors.py POINTER_BUTTON(RIGHT, DOWN, 0.5, 0.25) golden vector.
    @Test
    fun `serialize matches golden vector`() {
        val bytes = PointerButtonSerializer.serialize(
            PointerButtonEvent(
                PointerButtonEvent.Button.RIGHT,
                PointerButtonEvent.Action.DOWN,
                0.5f,
                0.25f,
            ),
        )
        assertEquals("01003F0000003E800000", bytes.toHex())
        assertEquals(PointerButtonEvent.SERIALIZED_SIZE, bytes.size)
    }

    @Test
    fun `round trip preserves all fields`() {
        val original = PointerButtonEvent(
            PointerButtonEvent.Button.LEFT,
            PointerButtonEvent.Action.UP,
            0.123f,
            0.987f,
        )
        assertEquals(original, PointerButtonSerializer.deserialize(PointerButtonSerializer.serialize(original)))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
