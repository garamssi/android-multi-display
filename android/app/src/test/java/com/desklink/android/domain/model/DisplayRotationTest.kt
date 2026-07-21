package com.desklink.android.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The 0/90/180/270 rotation decomposition: portrait geometry (sent to the Mac) vs the
 * tablet-only 180 flip. 90 vs 270 differ only by the flip bit.
 */
class DisplayRotationTest {

    @Test
    fun `degrees map to the right rotation`() {
        assertEquals(DisplayRotation.ROTATION_0, DisplayRotation.fromDegrees(0))
        assertEquals(DisplayRotation.ROTATION_90, DisplayRotation.fromDegrees(90))
        assertEquals(DisplayRotation.ROTATION_180, DisplayRotation.fromDegrees(180))
        assertEquals(DisplayRotation.ROTATION_270, DisplayRotation.fromDegrees(270))
    }

    @Test
    fun `unknown degrees fall back to 0`() {
        assertEquals(DisplayRotation.ROTATION_0, DisplayRotation.fromDegrees(45))
        assertEquals(DisplayRotation.ROTATION_0, DisplayRotation.fromDegrees(-1))
    }

    @Test
    fun `portrait is 90 and 270 only`() {
        assertFalse(DisplayRotation.ROTATION_0.isPortrait)
        assertTrue(DisplayRotation.ROTATION_90.isPortrait)
        assertFalse(DisplayRotation.ROTATION_180.isPortrait)
        assertTrue(DisplayRotation.ROTATION_270.isPortrait)
    }

    @Test
    fun `flipped is 180 and 270 only`() {
        assertFalse(DisplayRotation.ROTATION_0.isFlipped)
        assertFalse(DisplayRotation.ROTATION_90.isFlipped)
        assertTrue(DisplayRotation.ROTATION_180.isFlipped)
        assertTrue(DisplayRotation.ROTATION_270.isFlipped)
    }
}
