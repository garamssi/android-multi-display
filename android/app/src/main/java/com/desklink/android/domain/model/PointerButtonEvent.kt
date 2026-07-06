package com.desklink.android.domain.model

/**
 * A pointer button press or release at a normalized position, used to map a
 * single-finger long-press on the tablet to a right-click on the Mac. Coordinates
 * follow the same normalized [0,1] convention as [TouchEvent] (fraction of the view).
 * A full click is two events: [Action.DOWN] then [Action.UP].
 */
data class PointerButtonEvent(
    val button: Button,
    val action: Action,
    val x: Float,
    val y: Float,
) {
    /** Which mouse button the event targets. */
    enum class Button(val value: Byte) {
        LEFT(0x00),
        RIGHT(0x01),
    }

    /** Whether the button is being pressed or released. */
    enum class Action(val value: Byte) {
        DOWN(0x00),
        UP(0x01),
    }

    companion object {
        /** Wire payload size: Button(1) + Action(1) + X(f32) + Y(f32) = 10 bytes, BE. */
        const val SERIALIZED_SIZE = 10
    }
}
