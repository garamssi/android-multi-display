package com.desklink.android.presentation.display

import kotlin.math.hypot

class FlingDecay(
    val frameMs: Long = DEFAULT_FRAME_MS,
    private val frameDecay: Float = DEFAULT_FRAME_DECAY,
    private val minSpeed: Float = DEFAULT_MIN_SPEED,
) {
    fun isActive(velocityX: Float, velocityY: Float): Boolean =
        hypot(velocityX, velocityY) >= minSpeed

    fun step(velocityX: Float, velocityY: Float): Pair<Float, Float> =
        (velocityX * frameMs) to (velocityY * frameMs)

    fun decay(velocityX: Float, velocityY: Float): Pair<Float, Float> =
        (velocityX * frameDecay) to (velocityY * frameDecay)

    private companion object {
        const val DEFAULT_FRAME_MS = 16L

        const val DEFAULT_FRAME_DECAY = 0.92f

        const val DEFAULT_MIN_SPEED = 0.00005f
    }
}
