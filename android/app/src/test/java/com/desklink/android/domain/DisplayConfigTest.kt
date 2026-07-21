package com.desklink.android.domain

import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.DisplayRotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DisplayConfigTest {

    @Test
    fun `oriented keeps landscape for 0 and 180`() {
        val config = DisplayConfig(width = 2560, height = 1600, nativeWidth = 2560, nativeHeight = 1600)
        for (rotation in listOf(DisplayRotation.ROTATION_0, DisplayRotation.ROTATION_180)) {
            val out = config.oriented(rotation)
            assertEquals(2560, out.width)
            assertEquals(1600, out.height)
            // Native size is untouched (always landscape-normalised for the handshake).
            assertEquals(2560, out.nativeWidth)
            assertEquals(1600, out.nativeHeight)
        }
    }

    @Test
    fun `oriented swaps to portrait for 90 and 270`() {
        val config = DisplayConfig(width = 2560, height = 1600, nativeWidth = 2560, nativeHeight = 1600)
        for (rotation in listOf(DisplayRotation.ROTATION_90, DisplayRotation.ROTATION_270)) {
            val out = config.oriented(rotation)
            assertEquals(1600, out.width)
            assertEquals(2560, out.height)
            assertEquals(2560, out.nativeWidth)
            assertEquals(1600, out.nativeHeight)
        }
    }

    @Test
    fun `oriented is idempotent and normalises an already-portrait config`() {
        // Even if width/height arrive tall, oriented() derives long/short so the result
        // is deterministic for the rotation.
        val tall = DisplayConfig(width = 1600, height = 2560)
        assertEquals(2560, tall.oriented(DisplayRotation.ROTATION_0).width)
        assertEquals(1600, tall.oriented(DisplayRotation.ROTATION_0).height)
        assertEquals(1600, tall.oriented(DisplayRotation.ROTATION_90).width)
        assertEquals(2560, tall.oriented(DisplayRotation.ROTATION_90).height)
    }

    @Test
    fun `default config has expected values`() {
        val config = DisplayConfig()
        assertEquals(1920, config.width)
        assertEquals(1200, config.height)
        assertEquals(60, config.fps)
        assertEquals(DisplayConfig.Codec.HEVC, config.codec)
        assertEquals(20_000, config.bitrateKbps)
        assertEquals(1920, config.nativeWidth)
        assertEquals(1200, config.nativeHeight)
    }

    @Test
    fun `codec fromId returns correct codec`() {
        assertEquals(DisplayConfig.Codec.HEVC, DisplayConfig.Codec.fromId(0x01))
        assertEquals(DisplayConfig.Codec.H264, DisplayConfig.Codec.fromId(0x02))
    }

    @Test
    fun `codec fromId throws on unknown id`() {
        assertThrows<IllegalArgumentException> {
            DisplayConfig.Codec.fromId(0x99.toByte())
        }
    }

    @Test
    fun `recommendedBitrateKbps scales with width`() {
        assertEquals(40_000, DisplayConfig.recommendedBitrateKbps(2560))
        assertEquals(40_000, DisplayConfig.recommendedBitrateKbps(3840))
        assertEquals(25_000, DisplayConfig.recommendedBitrateKbps(1920))
        assertEquals(15_000, DisplayConfig.recommendedBitrateKbps(1280))
        assertEquals(10_000, DisplayConfig.recommendedBitrateKbps(800))
    }

    @Test
    fun `forNativeResolution defaults to native size and matching bitrate`() {
        val config = DisplayConfig.forNativeResolution(2560, 1600)
        assertEquals(2560, config.width)
        assertEquals(1600, config.height)
        assertEquals(2560, config.nativeWidth)
        assertEquals(1600, config.nativeHeight)
        assertEquals(40_000, config.bitrateKbps)
        assertEquals(DisplayConfig.Codec.HEVC, config.codec)
        assertEquals(60, config.fps)
    }

    @Test
    fun `forNativeResolution normalises portrait metrics to landscape`() {
        val config = DisplayConfig.forNativeResolution(1600, 2560)
        assertEquals(2560, config.width)
        assertEquals(1600, config.height)
        assertEquals(2560, config.nativeWidth)
        assertEquals(1600, config.nativeHeight)
    }

    @Test
    fun `forNativeResolution falls back to defaults on invalid metrics`() {
        assertEquals(DisplayConfig(), DisplayConfig.forNativeResolution(0, 0))
        assertEquals(DisplayConfig(), DisplayConfig.forNativeResolution(-1, 1200))
    }
}
