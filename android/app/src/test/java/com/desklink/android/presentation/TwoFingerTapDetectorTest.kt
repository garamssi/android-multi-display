package com.desklink.android.presentation

import com.desklink.android.presentation.display.PointerPhase
import com.desklink.android.presentation.display.TwoFingerTapDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Recognition rules for the "reveal controls" two-finger tap on the mirror surface:
 *  - a clean two-finger tap is recognized and reserved for the app (not forwarded);
 *  - single-finger touches are always forwarded (the remote cursor);
 *  - moving or holding too long, or a third finger, is not a tap;
 *  - the moment the second finger lands is flagged so the caller can release the
 *    in-flight single touch already sent to the Mac.
 */
class TwoFingerTapDetectorTest {

    @Test
    fun `clean two-finger tap is recognized and suppressed from forwarding`() {
        val d = TwoFingerTapDetector()
        // finger 1 down (single touch -> forwarded)
        assertFalse(d.onEvent(PointerPhase.DOWN, 1, 0, 100f, 100f).suppressForward)
        // finger 2 lands -> enters multi-touch, must release the Mac's in-flight touch
        val enter = d.onEvent(PointerPhase.POINTER_DOWN, 2, 10, 100f, 100f)
        assertTrue(enter.suppressForward)
        assertTrue(enter.enteredMultiTouch)
        // one finger lifts, then the last finger lifts quickly without movement
        assertTrue(d.onEvent(PointerPhase.POINTER_UP, 2, 60, 100f, 100f).suppressForward)
        val up = d.onEvent(PointerPhase.UP, 1, 80, 100f, 100f)
        assertTrue(up.twoFingerTap)
        assertTrue(up.suppressForward) // the whole gesture stays app-owned
    }

    @Test
    fun `single-finger tap is forwarded and is not a two-finger tap`() {
        val d = TwoFingerTapDetector()
        assertFalse(d.onEvent(PointerPhase.DOWN, 1, 0, 50f, 50f).suppressForward)
        val up = d.onEvent(PointerPhase.UP, 1, 40, 50f, 50f)
        assertFalse(up.twoFingerTap)
        assertFalse(up.suppressForward)
    }

    @Test
    fun `two fingers held too long is not a tap`() {
        val d = TwoFingerTapDetector(maxTapDurationMs = 300L)
        d.onEvent(PointerPhase.DOWN, 1, 0, 100f, 100f)
        d.onEvent(PointerPhase.POINTER_DOWN, 2, 0, 100f, 100f)
        val up = d.onEvent(PointerPhase.UP, 1, 500, 100f, 100f) // 500ms > 300ms
        assertFalse(up.twoFingerTap)
    }

    @Test
    fun `two fingers with movement is a scroll, not a tap`() {
        val d = TwoFingerTapDetector(moveSlopPx = 40f)
        d.onEvent(PointerPhase.DOWN, 1, 0, 100f, 100f)
        d.onEvent(PointerPhase.POINTER_DOWN, 2, 10, 100f, 100f)
        d.onEvent(PointerPhase.MOVE, 2, 30, 100f, 200f) // moved 100px
        val up = d.onEvent(PointerPhase.UP, 1, 60, 100f, 200f)
        assertFalse(up.twoFingerTap)
    }

    @Test
    fun `two-finger drag reports scroll deltas and no tap`() {
        val d = TwoFingerTapDetector()
        d.onEvent(PointerPhase.DOWN, 1, 0, 100f, 100f)
        d.onEvent(PointerPhase.POINTER_DOWN, 2, 5, 100f, 100f)

        val move1 = d.onEvent(PointerPhase.MOVE, 2, 20, 100f, 150f)
        assertTrue(move1.suppressForward)
        assertEquals(0f, move1.scrollDx, 0.0001f)
        assertEquals(50f, move1.scrollDy, 0.0001f) // moved down 50px since the 2nd finger landed

        val move2 = d.onEvent(PointerPhase.MOVE, 2, 40, 120f, 150f)
        assertEquals(20f, move2.scrollDx, 0.0001f) // per-event delta, not cumulative
        assertEquals(0f, move2.scrollDy, 0.0001f)

        val up = d.onEvent(PointerPhase.UP, 1, 60, 120f, 150f)
        assertFalse(up.twoFingerTap) // movement disqualifies the tap
    }

    @Test
    fun `single-finger move reports no scroll`() {
        val d = TwoFingerTapDetector()
        d.onEvent(PointerPhase.DOWN, 1, 0, 100f, 100f)
        val move = d.onEvent(PointerPhase.MOVE, 1, 20, 100f, 200f)
        assertEquals(0f, move.scrollDx, 0.0001f)
        assertEquals(0f, move.scrollDy, 0.0001f)
        assertFalse(move.suppressForward) // single finger is forwarded to the Mac
    }

    @Test
    fun `three fingers is not a two-finger tap`() {
        val d = TwoFingerTapDetector()
        d.onEvent(PointerPhase.DOWN, 1, 0, 100f, 100f)
        d.onEvent(PointerPhase.POINTER_DOWN, 2, 5, 100f, 100f)
        d.onEvent(PointerPhase.POINTER_DOWN, 3, 10, 100f, 100f)
        d.onEvent(PointerPhase.POINTER_UP, 3, 40, 100f, 100f)
        d.onEvent(PointerPhase.POINTER_UP, 2, 50, 100f, 100f)
        val up = d.onEvent(PointerPhase.UP, 1, 60, 100f, 100f)
        assertFalse(up.twoFingerTap)
    }
}
