package com.desklink.android.domain.model

data class PointerButtonEvent(
    val button: Button,
    val action: Action,
    val x: Float,
    val y: Float,
) {
    enum class Button(val value: Byte) {
        LEFT(0x00),
        RIGHT(0x01),
    }

    enum class Action(val value: Byte) {
        DOWN(0x00),
        UP(0x01),
    }

    companion object {
        const val SERIALIZED_SIZE = 10
    }
}
