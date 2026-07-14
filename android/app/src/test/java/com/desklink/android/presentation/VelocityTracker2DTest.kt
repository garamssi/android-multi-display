package com.desklink.android.presentation

import com.desklink.android.presentation.display.VelocityTracker2D
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class VelocityTracker2DTest {

    @Test
    fun `converges to the sustained velocity`() {
        val tracker = VelocityTracker2D()
        // 0.02 of the view every 16 ms -> 0.00125 units/ms.
        repeat(50) { tracker.track(0.02f, 0f, 16L) }
        assertEquals(0.00125f, tracker.velocityX, 1e-5f)
        assertEquals(0f, tracker.velocityY, 1e-6f)
    }

    @Test
    fun `stalling pulls velocity toward zero`() {
        val tracker = VelocityTracker2D()
        repeat(20) { tracker.track(0f, 0.02f, 16L) }
        val moving = abs(tracker.velocityY)
        repeat(10) { tracker.track(0f, 0f, 16L) }
        assertTrue(abs(tracker.velocityY) < moving * 0.1f, "velocity should collapse after a stall")
    }

    @Test
    fun `reset zeroes the estimate`() {
        val tracker = VelocityTracker2D()
        tracker.track(0.05f, 0.05f, 16L)
        tracker.reset()
        assertEquals(0f, tracker.velocityX)
        assertEquals(0f, tracker.velocityY)
    }

    @Test
    fun `non-positive dt is ignored`() {
        val tracker = VelocityTracker2D()
        tracker.track(0.05f, 0.05f, 0L)
        tracker.track(0.05f, 0.05f, -5L)
        assertEquals(0f, tracker.velocityX)
        assertEquals(0f, tracker.velocityY)
    }
}
