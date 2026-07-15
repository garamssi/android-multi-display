package com.desklink.android.presentation

import com.desklink.android.presentation.display.ViewZoom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pinch/pan math for the local mirror zoom: scale clamps to [1, maxScale], the focal point
 * stays fixed under the fingers, the offset clamps so the content always covers the
 * viewport, screen->content mapping inverts the transform, and zooming back out resets.
 */
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
        // The content under the focal screen point (center) is unchanged: still 0.5.
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
        // At 2x anchored top-left, the visible right edge (screen 1000) is content 0.5.
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

    private companion object {
        const val EPS = 1e-3f
    }
}
