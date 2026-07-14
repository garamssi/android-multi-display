package com.desklink.android.presentation.display

// Transform is screen = content * scale + offset with a top-left pivot; contentNormalized* inverts it to Mac coords.
class ViewZoom(private val maxScale: Float = MAX_SCALE) {

    var scale: Float = 1f
        private set
    var offsetX: Float = 0f
        private set
    var offsetY: Float = 0f
        private set

    val isZoomed: Boolean get() = scale > 1f + EPSILON

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

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

    fun pan(dx: Float, dy: Float, viewW: Float, viewH: Float) {
        if (!isZoomed) return
        offsetX += dx
        offsetY += dy
        clamp(viewW, viewH)
    }

    // When flipped (180-rotated mirror), flip the screen coord (viewW - screenX) BEFORE inverting the zoom.
    fun contentNormalizedX(screenX: Float, viewW: Float, flipped: Boolean = false): Float {
        if (viewW <= 0f) return 0f
        val sx = if (flipped) viewW - screenX else screenX
        return ((sx - offsetX) / (scale * viewW)).coerceIn(0f, 1f)
    }

    fun contentNormalizedY(screenY: Float, viewH: Float, flipped: Boolean = false): Float {
        if (viewH <= 0f) return 0f
        val sy = if (flipped) viewH - screenY else screenY
        return ((sy - offsetY) / (scale * viewH)).coerceIn(0f, 1f)
    }

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
