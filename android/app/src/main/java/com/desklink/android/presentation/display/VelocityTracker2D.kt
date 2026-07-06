package com.desklink.android.presentation.display

/**
 * Estimates pointer velocity as an exponential moving average, in normalized units
 * (fraction of the view) per millisecond — the same units the scroll gesture sends.
 * Fed the per-event scroll delta and the time since the previous scroll event, so the
 * screen can hand the release velocity to a fling.
 *
 * Pure and Android-free (no `android.view.VelocityTracker`) so the estimate is
 * unit-testable, and because we track already-normalized deltas rather than raw px.
 *
 * A higher [smoothing] weights the most recent sample more (more responsive, noisier);
 * lower is steadier. The EMA naturally decays toward zero if the finger slows before
 * lifting, so a drag that stalls does not fling.
 */
class VelocityTracker2D(
    private val smoothing: Float = DEFAULT_SMOOTHING,
) {
    var velocityX = 0f
        private set
    var velocityY = 0f
        private set

    fun reset() {
        velocityX = 0f
        velocityY = 0f
    }

    /** Folds one sample (normalized delta over [dtMs]) into the running average. */
    fun track(deltaX: Float, deltaY: Float, dtMs: Long) {
        if (dtMs <= 0L) return
        val instantX = deltaX / dtMs
        val instantY = deltaY / dtMs
        velocityX = velocityX * (1f - smoothing) + instantX * smoothing
        velocityY = velocityY * (1f - smoothing) + instantY * smoothing
    }

    private companion object {
        const val DEFAULT_SMOOTHING = 0.5f
    }
}
