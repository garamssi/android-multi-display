package com.desklink.android.presentation

import com.desklink.android.presentation.display.LongPressDetector
import com.desklink.android.presentation.display.PointerPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Recognition rules for the single-finger long-press mapped to a right-click:
 *  - a stationary hold past the threshold fires exactly once;
 *  - it never fires before the threshold;
 *  - lifting, moving beyond the slop, or a second finger disqualifies it;
 *  - the DOWN position is captured as the right-click anchor.
 */
class LongPressDetectorTest {

    @Test
    fun `stationary hold past threshold fires once`() {
        val d = LongPressDetector(longPressThresholdMs = 500L)
        d.onEvent(PointerPhase.DOWN, 1, 0, 100f, 200f)
        assertTrue(d.fireIfElapsed(500L))
        // A second check must not fire again for the same press.
        assertFalse(d.fireIfElapsed(600L))
    }

    @Test
    fun `does not fire before the threshold`() {
        val d = LongPressDetector(longPressThresholdMs = 500L)
        d.onEvent(PointerPhase.DOWN, 1, 0, 100f, 200f)
        assertFalse(d.fireIfElapsed(499L))
    }

    @Test
    fun `lifting before the threshold disqualifies`() {
        val d = LongPressDetector(longPressThresholdMs = 500L)
        d.onEvent(PointerPhase.DOWN, 1, 0, 100f, 200f)
        d.onEvent(PointerPhase.UP, 1, 200, 100f, 200f)
        assertFalse(d.fireIfElapsed(500L))
    }

    @Test
    fun `moving beyond the slop disqualifies`() {
        val d = LongPressDetector(longPressThresholdMs = 500L, moveSlopPx = 40f)
        d.onEvent(PointerPhase.DOWN, 1, 0, 100f, 200f)
        d.onEvent(PointerPhase.MOVE, 1, 100, 100f, 300f) // moved 100px
        assertFalse(d.fireIfElapsed(500L))
    }

    @Test
    fun `small jitter within the slop still fires`() {
        val d = LongPressDetector(longPressThresholdMs = 500L, moveSlopPx = 40f)
        d.onEvent(PointerPhase.DOWN, 1, 0, 100f, 200f)
        d.onEvent(PointerPhase.MOVE, 1, 100, 110f, 205f) // ~11px, within slop
        assertTrue(d.fireIfElapsed(500L))
    }

    @Test
    fun `second finger disqualifies`() {
        val d = LongPressDetector(longPressThresholdMs = 500L)
        d.onEvent(PointerPhase.DOWN, 1, 0, 100f, 200f)
        d.onEvent(PointerPhase.POINTER_DOWN, 2, 50, 100f, 200f)
        assertFalse(d.fireIfElapsed(500L))
    }

    @Test
    fun `captures the down position as the right-click anchor`() {
        val d = LongPressDetector()
        d.onEvent(PointerPhase.DOWN, 1, 0, 123f, 456f)
        assertEquals(123f, d.anchorX)
        assertEquals(456f, d.anchorY)
    }
}
