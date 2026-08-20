package com.desklink.android.presentation.display

import kotlin.math.hypot

// DOWN is forwarded as a left press immediately, so on fire the caller must release it (cancel) before injecting the right-click.
class LongPressDetector(
    val longPressThresholdMs: Long = DEFAULT_LONG_PRESS_MS,
    private val moveSlopPx: Float = DEFAULT_MOVE_SLOP_PX,
) {
    private var armed = false
    private var fired = false
    private var downTimeMs = 0L
    private var startX = 0f
    private var startY = 0f

    var anchorX = 0f
        private set
    var anchorY = 0f
        private set

    fun onEvent(phase: PointerPhase, pointerCount: Int, eventTimeMs: Long, x: Float, y: Float) {
        when (phase) {
            PointerPhase.DOWN -> {
                armed = true
                fired = false
                downTimeMs = eventTimeMs
                startX = x
                startY = y
                anchorX = x
                anchorY = y
            }

            PointerPhase.POINTER_DOWN -> disarm()

            PointerPhase.MOVE ->
                if (armed && hypot(x - startX, y - startY) > moveSlopPx) disarm()

            PointerPhase.UP, PointerPhase.CANCEL, PointerPhase.POINTER_UP -> disarm()
        }
    }

    fun fireIfElapsed(nowMs: Long): Boolean {
        if (armed && !fired && nowMs - downTimeMs >= longPressThresholdMs) {
            fired = true
            armed = false
            return true
        }
        return false
    }

    private fun disarm() {
        armed = false
    }

    private companion object {
        const val DEFAULT_LONG_PRESS_MS = 500L
        const val DEFAULT_MOVE_SLOP_PX = 40f
    }
}
