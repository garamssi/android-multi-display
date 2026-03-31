package com.desklink.android.data

import com.desklink.android.data.input.TouchSerializer
import com.desklink.android.domain.model.TouchEvent
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TouchSerializerTest {

    @Test
    fun `serialize produces correct size`() {
        val event = createTestEvent()
        val bytes = TouchSerializer.serialize(event)
        assertEquals(TouchEvent.SERIALIZED_SIZE, bytes.size)
    }

    @Test
    fun `round trip preserves values`() {
        val original = TouchEvent(
            action = TouchEvent.Action.DOWN,
            x = 0.5f,
            y = 0.75f,
            pressure = 32768u.toUShort(),
            pointerId = 2u.toUByte(),
            timestampUs = 1234567890L,
        )
        val bytes = TouchSerializer.serialize(original)
        val restored = TouchSerializer.deserialize(bytes)

        assertEquals(original.action, restored.action)
        assertEquals(original.x, restored.x, 0.0001f)
        assertEquals(original.y, restored.y, 0.0001f)
        assertEquals(original.pressure, restored.pressure)
        assertEquals(original.pointerId, restored.pointerId)
        assertEquals(original.timestampUs, restored.timestampUs)
    }

    @Test
    fun `serialize batch includes count header`() {
        val events = listOf(createTestEvent(), createTestEvent())
        val bytes = TouchSerializer.serializeBatch(events)
        // 2 bytes count + 2 * 20 bytes events = 42
        assertEquals(2 + 2 * TouchEvent.SERIALIZED_SIZE, bytes.size)
    }

    @Test
    fun `all actions round trip correctly`() {
        for (action in TouchEvent.Action.entries) {
            val event = TouchEvent(
                action = action,
                x = 0.1f,
                y = 0.9f,
                pressure = 100u.toUShort(),
                pointerId = 0u.toUByte(),
                timestampUs = 999L,
            )
            val restored = TouchSerializer.deserialize(TouchSerializer.serialize(event))
            assertEquals(action, restored.action, "Action mismatch for $action")
        }
    }

    private fun createTestEvent() = TouchEvent(
        action = TouchEvent.Action.MOVE,
        x = 0.5f,
        y = 0.5f,
        pressure = 0u.toUShort(),
        pointerId = 0u.toUByte(),
        timestampUs = 0L,
    )
}
