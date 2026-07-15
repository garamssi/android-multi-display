package com.desklink.android.presentation.display

import kotlin.math.hypot

/**
 * Recognizes a single-finger long-press (press-and-hold without moving) so it can be
 * mapped to a right-click on the Mac.
 *
 * The press is "armed" on a single-finger DOWN and stays armed until it is
 * disqualified by a second finger (multi-touch is reserved for the app), by moving
 * beyond [moveSlopPx], or by the finger lifting. Timing is external: the caller
 * schedules a check [longPressThresholdMs] after DOWN and calls [fireIfElapsed].
 * Keeping the clock out of this class leaves the arming/disqualification logic pure
 * and unit-testable.
 *
 * The single-finger DOWN is forwarded to the Mac immediately as a left press for
 * responsive dragging, so when a long-press fires the caller must first release that
 * left press (cancel) before injecting the right-click.
 */
class LongPressDetector(
    val longPressThresholdMs: Long = DEFAULT_LONG_PRESS_MS,
    private val moveSlopPx: Float = DEFAULT_MOVE_SLOP_PX,
) {
    private var armed = false
    private var fired = false
    private var downTimeMs = 0L
    private var startX = 0f
    private var startY = 0f

    /** Primary-pointer px position captured at DOWN — the right-click target. */
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

            // A second finger makes this an app-owned multi-touch gesture, not a press.
            PointerPhase.POINTER_DOWN -> disarm()

            PointerPhase.MOVE ->
                if (armed && hypot(x - startX, y - startY) > moveSlopPx) disarm()

            // Any lift ends the candidate press (a quick tap is a left-click, not a hold).
            PointerPhase.UP, PointerPhase.CANCEL, PointerPhase.POINTER_UP -> disarm()
        }
    }

    /**
     * Returns true exactly once when, at [nowMs], the press is still armed and has been
     * held at least [longPressThresholdMs]. The caller then injects the right-click.
     */
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
