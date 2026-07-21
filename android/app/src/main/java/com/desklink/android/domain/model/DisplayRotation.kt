package com.desklink.android.domain.model

/**
 * The user-selected screen rotation for the mirror.
 *
 * A rotation decomposes into two independent parts (see docs/protocol-spec.md 3.5):
 *  - [isPortrait]: the Mac builds the virtual display in that geometry (portrait sends a
 *    tall requested resolution, height > width). This is the only part carried on the wire.
 *  - [isFlipped]: a lossless 180 turn applied entirely on the tablet (view rotation + touch
 *    coordinate inversion). It is never sent to the Mac.
 *
 * The four rotations map as:
 *  - 0   = landscape, no flip
 *  - 90  = portrait,  no flip
 *  - 180 = landscape, flip
 *  - 270 = portrait,  flip   (== 90 + 180)
 *
 * So 90 vs 270 (the "left vs right" portrait) is exactly the flip bit; a 180 turn has no
 * left/right distinction (it is the same either way) and is a true rotation, not a mirror.
 */
enum class DisplayRotation(val degrees: Int) {
    ROTATION_0(0),
    ROTATION_90(90),
    ROTATION_180(180),
    ROTATION_270(270);

    /** Portrait geometry: the requested resolution is sent tall (height > width). */
    val isPortrait: Boolean get() = this == ROTATION_90 || this == ROTATION_270

    /** The tablet applies a lossless 180 turn (view + touch), not sent to the Mac. */
    val isFlipped: Boolean get() = this == ROTATION_180 || this == ROTATION_270

    companion object {
        fun fromDegrees(degrees: Int): DisplayRotation =
            entries.firstOrNull { it.degrees == degrees } ?: ROTATION_0
    }
}
