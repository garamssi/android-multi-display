package com.desklink.android.presentation.display

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
