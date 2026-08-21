package com.desklink.android.presentation

import com.desklink.android.presentation.display.VideoLayout
import com.desklink.android.presentation.display.VideoSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Fit keeps every pixel and pays with bars; Fill uses the whole panel and pays by cropping.
// Both keep the picture's own shape -- stretching it is the one answer nobody asked for.
class VideoLayoutTest {

    private val macOnTablet = VideoSize(1512, 982) // 1.539 on a 1.600 panel
    private val panelW = 3200
    private val panelH = 2000

    @Test
    fun `fit stays inside the panel`() {
        val size = VideoLayout.fit(macOnTablet, panelW, panelH)

        assertEquals(2000, size.height, "height is the limit, so it is used in full")
        assertEquals(3079, size.width)
        assertTrue(size.width <= panelW && size.height <= panelH)
    }

    @Test
    fun `fill covers the panel`() {
        val size = VideoLayout.fill(macOnTablet, panelW, panelH)

        assertEquals(3200, size.width, "width is the limit now, so it is covered exactly")
        assertEquals(2078, size.height)
        assertTrue(size.width >= panelW && size.height >= panelH, "anything less leaves a gap")
    }

    @Test
    fun `both keep the picture's shape`() {
        val streamAspect = 1512.0 / 982.0
        for (size in listOf(
            VideoLayout.fit(macOnTablet, panelW, panelH),
            VideoLayout.fill(macOnTablet, panelW, panelH),
        )) {
            val aspect = size.width.toDouble() / size.height
            assertTrue(
                kotlin.math.abs(streamAspect - aspect) < 0.001,
                "stream $streamAspect vs laid out $aspect",
            )
        }
    }

    // Extend builds the display from the tablet's own resolution, so the shapes match and
    // neither choice may move anything.
    @Test
    fun `a matching shape is untouched by either choice`() {
        val matching = VideoSize(1600, 1000)

        assertEquals(VideoLayout.fit(matching, panelW, panelH), VideoLayout.fill(matching, panelW, panelH))
        assertEquals(panelW, VideoLayout.fit(matching, panelW, panelH).width)
        assertEquals(panelH, VideoLayout.fit(matching, panelW, panelH).height)
    }

    @Test
    fun `a wider stream is cropped left and right by fill, not top and bottom`() {
        val wide = VideoSize(2000, 1000) // 2.0 on a 1.6 panel

        val filled = VideoLayout.fill(wide, panelW, panelH)
        assertEquals(4000, filled.width)
        assertEquals(2000, filled.height)
    }

    // Before the first frame there is no size, and a zero must not collapse the view or
    // divide by zero.
    @Test
    fun `an unknown stream size falls back to the panel`() {
        assertEquals(VideoSize(panelW, panelH), VideoLayout.fit(null, panelW, panelH))
        assertEquals(VideoSize(panelW, panelH), VideoLayout.fill(null, panelW, panelH))
        assertEquals(VideoSize(panelW, panelH), VideoLayout.fill(VideoSize(0, 0), panelW, panelH))
    }
}
