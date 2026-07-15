package com.desklink.android.presentation.display

/**
 * Local pinch-zoom + pan for the mirror view. Purely client-side: it scales/translates the
 * rendered mirror on the tablet (the Mac is not involved). All inputs are screen-space
 * pixels; the transform is clamped so the scaled content always covers the viewport.
 *
 * The mirror surface is transformed as `screen = content * scale + offset` (pivot at the
 * top-left), so [contentNormalizedX]/[contentNormalizedY] invert that to map a screen touch
 * back to the underlying Mac coordinate — keeping taps accurate while zoomed.
 *
 * Pure and Android-free so the math is unit-testable.
 */
class ViewZoom(private val maxScale: Float = MAX_SCALE) {

    var scale: Float = 1f
        private set
    var offsetX: Float = 0f
        private set
    var offsetY: Float = 0f
        private set

    /** True once zoomed in past a tiny epsilon (so a two-finger drag pans instead of scrolls). */
    val isZoomed: Boolean get() = scale > 1f + EPSILON

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    /**
     * Multiplies the scale by [ratio], keeping the screen focal point (fx, fy) fixed under
     * the fingers. Clamps scale to [1, maxScale] and re-clamps the offset.
     */
    fun pinch(ratio: Float, fx: Float, fy: Float, viewW: Float, viewH: Float) {
        if (viewW <= 0f || viewH <= 0f || !ratio.isFinite() || ratio <= 0f) return
        val newScale = (scale * ratio).coerceIn(1f, maxScale)
        if (newScale == scale) return
        // Keep the content point under (fx, fy) stationary: offset' = f - (f - offset) * k.
        val k = newScale / scale
        offsetX = fx - (fx - offsetX) * k
        offsetY = fy - (fy - offsetY) * k
        scale = newScale
        clamp(viewW, viewH)
    }

    /** Pans by a screen-space delta, clamped so the content can't leave the viewport. */
    fun pan(dx: Float, dy: Float, viewW: Float, viewH: Float) {
        if (!isZoomed) return
        offsetX += dx
        offsetY += dy
        clamp(viewW, viewH)
    }

    /** Normalized [0,1] content X for a screen X, inverting the current transform. */
    fun contentNormalizedX(screenX: Float, viewW: Float): Float =
        if (viewW <= 0f) 0f else ((screenX - offsetX) / (scale * viewW)).coerceIn(0f, 1f)

    /** Normalized [0,1] content Y for a screen Y, inverting the current transform. */
    fun contentNormalizedY(screenY: Float, viewH: Float): Float =
        if (viewH <= 0f) 0f else ((screenY - offsetY) / (scale * viewH)).coerceIn(0f, 1f)

    private fun clamp(viewW: Float, viewH: Float) {
        if (scale <= 1f + EPSILON) {
            reset()
            return
        }
        // Left/top offset is <= 0 and >= viewSize*(1 - scale) so both edges stay covered.
        offsetX = offsetX.coerceIn(viewW - viewW * scale, 0f)
        offsetY = offsetY.coerceIn(viewH - viewH * scale, 0f)
    }

    companion object {
        const val MAX_SCALE = 4f
        private const val EPSILON = 1e-3f
    }
}
