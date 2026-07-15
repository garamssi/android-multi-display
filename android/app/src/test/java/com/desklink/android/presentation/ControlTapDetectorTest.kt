package com.desklink.android.presentation

import com.desklink.android.presentation.display.ControlTapDetector
import com.desklink.android.presentation.display.PointerPhase
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Recognition rules for the three-finger control tap:
 *  - a short, near-stationary gesture whose peak count reaches exactly three taps;
 *  - one- or two-finger gestures never trigger it;
 *  - movement past the slop or an over-long hold disqualifies it;
 *  - any 2+ finger gesture reports suppressForward, with enteredMultiTouch firing once.
 */
class ControlTapDetectorTest {

    @Test
    fun `clean three-finger tap is recognized`() {
        val d = ControlTapDetector()
        d.onEvent(PointerPhase.DOWN, 1, 0, 0f, 0f)
        d.onEvent(PointerPhase.POINTER_DOWN, 2, 10, 0f, 0f)
        d.onEvent(PointerPhase.POINTER_DOWN, 3, 20, 0f, 0f)
        d.onEvent(PointerPhase.POINTER_UP, 3, 100, 0f, 0f)
        d.onEvent(PointerPhase.POINTER_UP, 2, 100, 0f, 0f)
        val out = d.onEvent(PointerPhase.UP, 1, 100, 0f, 0f)
        assertTrue(out.controlTap)
    }

    @Test
    fun `two-finger tap is not a control tap`() {
        val d = ControlTapDetector()
        d.onEvent(PointerPhase.DOWN, 1, 0, 0f, 0f)
        d.onEvent(PointerPhase.POINTER_DOWN, 2, 10, 0f, 0f)
        d.onEvent(PointerPhase.POINTER_UP, 2, 80, 0f, 0f)
        val out = d.onEvent(PointerPhase.UP, 1, 80, 0f, 0f)
        assertFalse(out.controlTap)
    }

    @Test
    fun `single-finger tap is not a control tap`() {
        val d = ControlTapDetector()
        d.onEvent(PointerPhase.DOWN, 1, 0, 0f, 0f)
        val out = d.onEvent(PointerPhase.UP, 1, 50, 0f, 0f)
        assertFalse(out.controlTap)
    }

    @Test
    fun `movement past the slop disqualifies the tap`() {
        val d = ControlTapDetector(moveSlopPx = 40f)
        d.onEvent(PointerPhase.DOWN, 1, 0, 0f, 0f)
        d.onEvent(PointerPhase.POINTER_DOWN, 2, 10, 0f, 0f)
        d.onEvent(PointerPhase.POINTER_DOWN, 3, 20, 0f, 0f)
        d.onEvent(PointerPhase.MOVE, 3, 50, 200f, 0f) // primary moved far
        val out = d.onEvent(PointerPhase.UP, 1, 100, 200f, 0f)
        assertFalse(out.controlTap)
    }

    @Test
    fun `an over-long hold is not a tap`() {
        val d = ControlTapDetector(maxTapDurationMs = 300L)
        d.onEvent(PointerPhase.DOWN, 1, 0, 0f, 0f)
        d.onEvent(PointerPhase.POINTER_DOWN, 2, 10, 0f, 0f)
        d.onEvent(PointerPhase.POINTER_DOWN, 3, 20, 0f, 0f)
        val out = d.onEvent(PointerPhase.UP, 1, 800, 0f, 0f)
        assertFalse(out.controlTap)
    }

    @Test
    fun `multi-touch suppresses forwarding and signals entry once`() {
        val d = ControlTapDetector()
        val down = d.onEvent(PointerPhase.DOWN, 1, 0, 0f, 0f)
        assertFalse(down.suppressForward)

        val second = d.onEvent(PointerPhase.POINTER_DOWN, 2, 10, 0f, 0f)
        assertTrue(second.suppressForward)
        assertTrue(second.enteredMultiTouch)

        val third = d.onEvent(PointerPhase.POINTER_DOWN, 3, 20, 0f, 0f)
        assertTrue(third.suppressForward)
        assertFalse(third.enteredMultiTouch) // only once

        val move = d.onEvent(PointerPhase.MOVE, 3, 30, 0f, 0f)
        assertTrue(move.suppressForward)
    }
}
