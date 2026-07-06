package com.desklink.android.presentation

import com.desklink.android.presentation.display.FlingDecay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The fling model must emit velocity*frameMs per step, apply the friction multiplier,
 * treat a speed below the floor as inactive, and always terminate.
 */
class FlingDecayTest {

    @Test
    fun `step emits velocity times the frame duration`() {
        val fling = FlingDecay(frameMs = 16L, frameDecay = 0.9f, minSpeed = 0.0001f)
        val (dx, dy) = fling.step(0.001f, -0.002f)
        assertEquals(0.016f, dx, 1e-6f)
        assertEquals(-0.032f, dy, 1e-6f)
    }

    @Test
    fun `decay multiplies velocity by the friction factor`() {
        val fling = FlingDecay(frameMs = 16L, frameDecay = 0.5f, minSpeed = 0.0001f)
        val (vx, vy) = fling.decay(1f, -2f)
        assertEquals(0.5f, vx, 1e-6f)
        assertEquals(-1f, vy, 1e-6f)
    }

    @Test
    fun `active only above the minimum speed`() {
        val fling = FlingDecay(minSpeed = 0.01f)
        assertTrue(fling.isActive(0.02f, 0f))
        assertFalse(fling.isActive(0.001f, 0f))
        assertFalse(fling.isActive(0f, 0f))
    }

    @Test
    fun `a fling always terminates`() {
        val fling = FlingDecay(frameMs = 16L, frameDecay = 0.9f, minSpeed = 0.0001f)
        var vx = 0.01f
        var vy = 0.01f
        var steps = 0
        while (fling.isActive(vx, vy)) {
            val decayed = fling.decay(vx, vy)
            vx = decayed.first
            vy = decayed.second
            steps++
            assertTrue(steps < 1000, "fling must decay to a stop")
        }
        assertTrue(steps > 0)
    }
}
