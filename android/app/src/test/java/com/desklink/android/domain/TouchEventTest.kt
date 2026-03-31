package com.desklink.android.domain

import com.desklink.android.domain.model.TouchEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TouchEventTest {

    @Test
    fun `serialized size matches protocol spec`() {
        // Protocol spec: Action(1) + X(4) + Y(4) + Pressure(2) + PointerID(1) + Timestamp(8) = 20
        assertEquals(20, TouchEvent.SERIALIZED_SIZE)
    }

    @Test
    fun `action fromCode returns correct action`() {
        assertEquals(TouchEvent.Action.DOWN, TouchEvent.Action.fromCode(0x00))
        assertEquals(TouchEvent.Action.UP, TouchEvent.Action.fromCode(0x01))
        assertEquals(TouchEvent.Action.MOVE, TouchEvent.Action.fromCode(0x02))
        assertEquals(TouchEvent.Action.CANCEL, TouchEvent.Action.fromCode(0x03))
    }

    @Test
    fun `action fromCode throws on unknown code`() {
        assertThrows<IllegalArgumentException> {
            TouchEvent.Action.fromCode(0x99.toByte())
        }
    }

    @Test
    fun `coordinates are validated between 0 and 1`() {
        val event = TouchEvent(
            action = TouchEvent.Action.DOWN,
            x = 0.5f,
            y = 0.75f,
            pressure = 32768u.toUShort(),
            pointerId = 0u.toUByte(),
            timestampUs = System.nanoTime() / 1000,
        )
        assertEquals(0.5f, event.x)
        assertEquals(0.75f, event.y)
    }

    @Test
    fun `x coordinate out of range throws`() {
        assertThrows<IllegalArgumentException> {
            TouchEvent(
                action = TouchEvent.Action.DOWN,
                x = 1.5f,
                y = 0.5f,
                pressure = 0u.toUShort(),
                pointerId = 0u.toUByte(),
                timestampUs = 0L,
            )
        }
    }

    @Test
    fun `y coordinate out of range throws`() {
        assertThrows<IllegalArgumentException> {
            TouchEvent(
                action = TouchEvent.Action.DOWN,
                x = 0.5f,
                y = -0.1f,
                pressure = 0u.toUShort(),
                pointerId = 0u.toUByte(),
                timestampUs = 0L,
            )
        }
    }
}
