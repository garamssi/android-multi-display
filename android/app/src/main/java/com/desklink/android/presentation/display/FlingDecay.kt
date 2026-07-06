package com.desklink.android.presentation.display

import kotlin.math.hypot

/**
 * Exponential-friction fling model for inertial scrolling. After the fingers lift, the
 * caller steps this model once per [frameMs]: emit [step] as the scroll delta for the
 * frame, then [decay] the velocity, repeating while [isActive]. This produces the
 * "glide" a real tablet gives after a flick, using ordinary SCROLL messages (no wire
 * change) — the Mac keeps injecting deltas 1:1 and its sub-pixel residual smooths them.
 *
 * Pure (no coroutines/clock) so the physics is unit-testable; the caller owns the loop
 * and timing. Velocity and deltas are in normalized units (fraction of the view).
 */
class FlingDecay(
    val frameMs: Long = DEFAULT_FRAME_MS,
    private val frameDecay: Float = DEFAULT_FRAME_DECAY,
    private val minSpeed: Float = DEFAULT_MIN_SPEED,
) {
    /** True while the given velocity still produces meaningful motion. */
    fun isActive(velocityX: Float, velocityY: Float): Boolean =
        hypot(velocityX, velocityY) >= minSpeed

    /** Normalized scroll delta to emit for one [frameMs] step at this velocity. */
    fun step(velocityX: Float, velocityY: Float): Pair<Float, Float> =
        (velocityX * frameMs) to (velocityY * frameMs)

    /** Velocity after one step of friction. */
    fun decay(velocityX: Float, velocityY: Float): Pair<Float, Float> =
        (velocityX * frameDecay) to (velocityY * frameDecay)

    private companion object {
        /** ~60 Hz stepping. */
        const val DEFAULT_FRAME_MS = 16L

        /** Per-frame velocity multiplier. ~0.92^62 ≈ 0.6% after 1s (roughly 0.8s glide). */
        const val DEFAULT_FRAME_DECAY = 0.92f

        /** Stop once the speed would emit only a sub-pixel-scale delta. */
        const val DEFAULT_MIN_SPEED = 0.00005f
    }
}
