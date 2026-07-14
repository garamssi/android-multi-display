package com.desklink.android.presentation

import com.desklink.android.presentation.display.ViewZoom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewZoomTest {

    private val w = 1000f
    private val h = 1000f

    @Test
    fun `starts unzoomed at identity`() {
        val z = ViewZoom()
        assertEquals(1f, z.scale, EPS)
        assertEquals(0f, z.offsetX, EPS)
        assertFalse(z.isZoomed)
    }

    @Test
    fun `pinch scales and keeps the focal point fixed`() {
        val z = ViewZoom(maxScale = 4f)
        z.pinch(ratio = 2f, fx = 500f, fy = 500f, viewW = w, viewH = h)

        assertEquals(2f, z.scale, EPS)
        assertTrue(z.isZoomed)
        assertEquals(0.5f, z.contentNormalizedX(500f, w), EPS)
        assertEquals(0.5f, z.contentNormalizedY(500f, h), EPS)
    }

    @Test
    fun `scale clamps to maxScale`() {
        val z = ViewZoom(maxScale = 4f)
        z.pinch(ratio = 100f, fx = 0f, fy = 0f, viewW = w, viewH = h)
        assertEquals(4f, z.scale, EPS)
    }

    @Test
    fun `pan clamps so content covers the viewport`() {
        val z = ViewZoom(maxScale = 4f)
        z.pinch(ratio = 4f, fx = 0f, fy = 0f, viewW = w, viewH = h) // offset 0, range [-3000, 0]
        z.pan(dx = 9999f, dy = 9999f, viewW = w, viewH = h)
        assertEquals(0f, z.offsetX, EPS) // can't pan past the left/top edge
        z.pan(dx = -9999f, dy = -9999f, viewW = w, viewH = h)
        assertEquals(-3000f, z.offsetX, EPS) // clamped to viewW*(1 - scale)
        assertEquals(-3000f, z.offsetY, EPS)
    }

    @Test
    fun `content mapping inverts the transform when zoomed`() {
        val z = ViewZoom(maxScale = 4f)
        z.pinch(ratio = 2f, fx = 0f, fy = 0f, viewW = w, viewH = h) // top-left focal -> offset 0
        assertEquals(0f, z.contentNormalizedX(0f, w), EPS)
        assertEquals(0.5f, z.contentNormalizedX(1000f, w), EPS)
    }

    @Test
    fun `zooming back out resets scale and offset`() {
        val z = ViewZoom()
        z.pinch(ratio = 3f, fx = 500f, fy = 500f, viewW = w, viewH = h)
        z.pinch(ratio = 0.001f, fx = 500f, fy = 500f, viewW = w, viewH = h)
        assertEquals(1f, z.scale, EPS)
        assertEquals(0f, z.offsetX, EPS)
        assertEquals(0f, z.offsetY, EPS)
        assertFalse(z.isZoomed)
    }

    @Test
    fun `flip inverts the screen-to-content mapping at identity`() {
        val z = ViewZoom()
        assertEquals(0.25f, z.contentNormalizedX(250f, w), EPS)
        assertEquals(0.75f, z.contentNormalizedX(250f, w, flipped = true), EPS)
        assertEquals(0.2f, z.contentNormalizedY(200f, h), EPS)
        assertEquals(0.8f, z.contentNormalizedY(200f, h, flipped = true), EPS)
        assertEquals(0.5f, z.contentNormalizedX(500f, w, flipped = true), EPS)
    }

    @Test
    fun `flip composes with zoom`() {
        val z = ViewZoom(maxScale = 4f)
        z.pinch(ratio = 2f, fx = 500f, fy = 500f, viewW = w, viewH = h)
        val screenX = 300f
        assertEquals(
            z.contentNormalizedX(w - screenX, w),
            z.contentNormalizedX(screenX, w, flipped = true),
            EPS,
        )
    }

    private companion object {
        const val EPS = 1e-3f
    }
}
