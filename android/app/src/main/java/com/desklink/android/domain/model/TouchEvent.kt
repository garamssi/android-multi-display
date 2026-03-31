package com.desklink.android.domain.model

data class TouchEvent(
    val action: Action,
    val x: Float,
    val y: Float,
    val pressure: UShort,
    val pointerId: UByte,
    val timestampUs: Long,
) {
    init {
        require(x in 0f..1f) { "x must be normalized (0.0-1.0), got $x" }
        require(y in 0f..1f) { "y must be normalized (0.0-1.0), got $y" }
    }

    enum class Action(val code: Byte) {
        DOWN(0x00),
        UP(0x01),
        MOVE(0x02),
        CANCEL(0x03);

        companion object {
            fun fromCode(code: Byte): Action =
                entries.firstOrNull { it.code == code }
                    ?: throw IllegalArgumentException("Unknown touch action code: $code")
        }
    }

    companion object {
        const val SERIALIZED_SIZE = 20 // 1 + 4 + 4 + 2 + 1 + 8
    }
}
