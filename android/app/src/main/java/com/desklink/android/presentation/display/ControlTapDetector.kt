package com.desklink.android.presentation.display

import kotlin.math.hypot

/** Coarse pointer phases the detectors understand (mapped from MotionEvent). */
enum class PointerPhase { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

/**
 * Recognizes a clean [requiredFingers]-finger tap (default three) used to reveal the
 * on-screen controls, and reports whether the gesture is multi-touch so single-finger
 * forwarding can be suppressed.
 *
 * Single-finger touches drive the Mac cursor and two-finger gestures scroll/zoom, so the
 * controls need a distinct gesture — a three-finger tap. A tap is a short, near-stationary
 * gesture whose peak finger count reaches exactly [requiredFingers].
 *
 * Pure and Android-free (fed coarse [PointerPhase]s + the primary pointer position) so the
 * recognition is unit-testable without instrumentation.
 */
class ControlTapDetector(
    private val requiredFingers: Int = DEFAULT_REQUIRED_FINGERS,
    private val maxTapDurationMs: Long = DEFAULT_MAX_TAP_DURATION_MS,
    private val moveSlopPx: Float = DEFAULT_MOVE_SLOP_PX,
) {
    private var peakPointers = 0
    private var startMs = 0L
    private var startX = 0f
    private var startY = 0f
    private var maxMovePx = 0f
    private var multiTouch = false

    /**
     * @property suppressForward true while the gesture involves two or more fingers, so
     *   it must NOT be forwarded to the Mac as a remote touch.
     * @property enteredMultiTouch true exactly once, when the second finger lands, so the
     *   caller can release any in-flight single touch on the Mac.
     * @property controlTap true when a clean [requiredFingers]-finger tap has completed.
     */
    data class Outcome(
        val suppressForward: Boolean,
        val enteredMultiTouch: Boolean,
        val controlTap: Boolean,
    )

    fun onEvent(
        phase: PointerPhase,
        pointerCount: Int,
        eventTimeMs: Long,
        primaryX: Float,
        primaryY: Float,
    ): Outcome {
        var enteredMultiTouch = false
        when (phase) {
            PointerPhase.DOWN -> {
                reset()
                peakPointers = 1
                startMs = eventTimeMs
                startX = primaryX
                startY = primaryY
            }

            PointerPhase.POINTER_DOWN -> {
                if (pointerCount > peakPointers) peakPointers = pointerCount
                if (pointerCount >= 2 && !multiTouch) {
                    multiTouch = true
                    enteredMultiTouch = true
                }
            }

            PointerPhase.MOVE -> {
                val move = hypot(primaryX - startX, primaryY - startY)
                if (move > maxMovePx) maxMovePx = move
            }

            PointerPhase.POINTER_UP -> {
                // A finger lifted but the gesture continues; keep suppressing so the
                // single-finger remainder isn't forwarded as a stray remote touch.
            }

            PointerPhase.UP -> {
                val tap = peakPointers == requiredFingers &&
                    eventTimeMs - startMs <= maxTapDurationMs &&
                    maxMovePx <= moveSlopPx
                val wasMulti = multiTouch
                reset()
                return Outcome(suppressForward = wasMulti, enteredMultiTouch = false, controlTap = tap)
            }

            PointerPhase.CANCEL -> {
                val wasMulti = multiTouch
                reset()
                return Outcome(suppressForward = wasMulti, enteredMultiTouch = false, controlTap = false)
            }
        }

        return Outcome(
            suppressForward = multiTouch,
            enteredMultiTouch = enteredMultiTouch,
            controlTap = false,
        )
    }

    private fun reset() {
        peakPointers = 0
        startMs = 0L
        maxMovePx = 0f
        multiTouch = false
    }

    private companion object {
        const val DEFAULT_REQUIRED_FINGERS = 3
        const val DEFAULT_MAX_TAP_DURATION_MS = 300L
        const val DEFAULT_MOVE_SLOP_PX = 40f
    }
}
