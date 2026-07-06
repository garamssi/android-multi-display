package com.desklink.android.presentation.display

import kotlin.math.hypot

/** Coarse pointer phases the detector understands (mapped from MotionEvent). */
enum class PointerPhase { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

/**
 * Outcome for a single fed pointer event.
 *
 * @property suppressForward true while the gesture is app-owned (multi-touch) and
 *   must NOT be forwarded to the Mac as a remote touch.
 * @property enteredMultiTouch true exactly once, on the event where the second
 *   finger lands, so the caller can release the in-flight single touch on the Mac.
 * @property twoFingerTap true when a clean two-finger tap has just completed.
 * @property scrollDx px delta of the primary pointer since the previous event while
 *   two fingers are down (a two-finger drag = scroll). 0 when not scrolling.
 * @property scrollDy see [scrollDx].
 */
data class GestureOutcome(
    val suppressForward: Boolean,
    val enteredMultiTouch: Boolean,
    val twoFingerTap: Boolean,
    val scrollDx: Float = 0f,
    val scrollDy: Float = 0f,
)

/**
 * Recognizes a two-finger tap so it can be used as a dedicated "reveal controls"
 * gesture on the mirror surface.
 *
 * The mirror forwards every single-finger touch to the Mac, so a single tap can't
 * double as a UI gesture. Two-finger (multi-touch) gestures are instead reserved for
 * the app: while a gesture involves two or more fingers it is suppressed from
 * forwarding, and if it ends quickly without movement it is reported as a tap.
 *
 * Pure and Android-free (fed coarse [PointerPhase]s and the primary pointer's px
 * position) so the recognition logic is unit-testable without instrumentation.
 */
class TwoFingerTapDetector(
    private val maxTapDurationMs: Long = DEFAULT_MAX_TAP_DURATION_MS,
    private val moveSlopPx: Float = DEFAULT_MOVE_SLOP_PX,
) {
    private var multiTouch = false
    private var disqualified = false
    private var twoFingerStartMs = 0L
    private var startX = 0f
    private var startY = 0f
    private var maxMovePx = 0f
    private var prevX = 0f
    private var prevY = 0f

    fun onEvent(
        phase: PointerPhase,
        pointerCount: Int,
        eventTimeMs: Long,
        primaryX: Float,
        primaryY: Float,
    ): GestureOutcome {
        var enteredMultiTouch = false
        var scrollDx = 0f
        var scrollDy = 0f
        when (phase) {
            PointerPhase.DOWN -> {
                resetGesture()
                startX = primaryX
                startY = primaryY
            }

            PointerPhase.POINTER_DOWN -> when {
                pointerCount == 2 && !multiTouch -> {
                    multiTouch = true
                    enteredMultiTouch = true
                    twoFingerStartMs = eventTimeMs
                    maxMovePx = 0f
                    prevX = primaryX
                    prevY = primaryY
                }
                pointerCount > 2 -> disqualified = true // three+ fingers is not a two-finger tap
            }

            PointerPhase.MOVE -> {
                val move = hypot(primaryX - startX, primaryY - startY)
                if (move > maxMovePx) maxMovePx = move
                // Scroll only while TWO fingers are actually down. If one lifted (count
                // drops to 1) we stop scrolling — and, because the gesture is still
                // multiTouch, we don't forward the lone finger as a raw touch either.
                // This also avoids a spurious delta when pointer index 0 changes after
                // a finger lift.
                if (multiTouch && pointerCount >= 2) {
                    scrollDx = primaryX - prevX
                    scrollDy = primaryY - prevY
                }
                prevX = primaryX
                prevY = primaryY
            }

            PointerPhase.UP -> {
                // Whole gesture ended (last finger lifted).
                val tap = multiTouch &&
                    !disqualified &&
                    eventTimeMs - twoFingerStartMs <= maxTapDurationMs &&
                    maxMovePx <= moveSlopPx
                val wasMulti = multiTouch
                resetGesture()
                return GestureOutcome(
                    suppressForward = wasMulti,
                    enteredMultiTouch = false,
                    twoFingerTap = tap,
                )
            }

            PointerPhase.CANCEL -> {
                val wasMulti = multiTouch
                resetGesture()
                return GestureOutcome(
                    suppressForward = wasMulti,
                    enteredMultiTouch = false,
                    twoFingerTap = false,
                )
            }

            PointerPhase.POINTER_UP -> {
                // A finger lifted but the gesture continues; keep suppressing so the
                // single-finger remainder isn't forwarded as a stray remote touch.
            }
        }

        return GestureOutcome(
            suppressForward = multiTouch,
            enteredMultiTouch = enteredMultiTouch,
            twoFingerTap = false,
            scrollDx = scrollDx,
            scrollDy = scrollDy,
        )
    }

    private fun resetGesture() {
        multiTouch = false
        disqualified = false
        twoFingerStartMs = 0L
        maxMovePx = 0f
    }

    private companion object {
        const val DEFAULT_MAX_TAP_DURATION_MS = 300L
        const val DEFAULT_MOVE_SLOP_PX = 40f
    }
}
