package com.desklink.android.domain.model

enum class DisplayRotation(val degrees: Int) {
    ROTATION_0(0),
    ROTATION_90(90),
    ROTATION_180(180),
    ROTATION_270(270);

    val isPortrait: Boolean get() = this == ROTATION_90 || this == ROTATION_270

    val isFlipped: Boolean get() = this == ROTATION_180 || this == ROTATION_270

    companion object {
        fun fromDegrees(degrees: Int): DisplayRotation =
            entries.firstOrNull { it.degrees == degrees } ?: ROTATION_0
    }
}
