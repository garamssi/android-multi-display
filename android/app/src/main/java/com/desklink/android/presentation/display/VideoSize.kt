package com.desklink.android.presentation.display

/**
 * The decoded picture's dimensions in pixels.
 *
 * Not the negotiated resolution: that is what the Mac was asked for, and in mirror mode it
 * captures its own screen's size instead. The view needs the real one to keep the picture's
 * shape instead of stretching it to the panel's.
 */
data class VideoSize(val width: Int, val height: Int)

/**
 * The size to lay the video out at, keeping the picture's own shape.
 *
 * Mirror sends the Mac's own screen, whose shape has nothing to do with this panel's. [fit]
 * keeps every pixel and leaves bars; [fill] covers the panel and lets the overflow be clipped.
 * Stretching to the panel is the one answer nobody wants, so neither does it.
 */
object VideoLayout {

    /** Largest size that fits inside the panel. */
    fun fit(video: VideoSize?, panelWidth: Int, panelHeight: Int): VideoSize =
        scaled(video, panelWidth, panelHeight) { wide, tall -> minOf(wide, tall) }

    /** Smallest size that covers the panel; the rest is clipped by the view holding it. */
    fun fill(video: VideoSize?, panelWidth: Int, panelHeight: Int): VideoSize =
        scaled(video, panelWidth, panelHeight) { wide, tall -> maxOf(wide, tall) }

    private inline fun scaled(
        video: VideoSize?,
        panelWidth: Int,
        panelHeight: Int,
        choose: (Double, Double) -> Double,
    ): VideoSize {
        // No size yet (nothing decoded) or a nonsense one: the panel is the only honest
        // answer, and scaling from zero would collapse the view or divide by zero.
        if (video == null || video.width <= 0 || video.height <= 0) {
            return VideoSize(panelWidth, panelHeight)
        }
        if (panelWidth <= 0 || panelHeight <= 0) return VideoSize(0, 0)

        val scale = choose(
            panelWidth.toDouble() / video.width,
            panelHeight.toDouble() / video.height,
        )
        return VideoSize(
            width = Math.round(video.width * scale).toInt(),
            height = Math.round(video.height * scale).toInt(),
        )
    }
}
