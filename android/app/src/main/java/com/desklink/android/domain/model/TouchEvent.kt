package com.desklink.android.domain.model

data class TouchEvent(
    val action: Action,
    val x: Float,
    val y: Float,
    val pressure: UShort,
    val pointerId: UByte,
    val timestampUs: Long,
) {
    enum class Action(val code: Byte) {
        DOWN(0x00),
        UP(0x01),
        MOVE(0x02),
        CANCEL(0x03);

        companion object {
            fun fromCode(code: Byte): Action = entries.first { it.code == code }
        }
    }

    companion object {
        const val SERIALIZED_SIZE = 20 // 1 + 4 + 4 + 2 + 1 + 8
    }
}
