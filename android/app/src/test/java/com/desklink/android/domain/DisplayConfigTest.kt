package com.desklink.android.domain

import com.desklink.android.domain.model.DisplayConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DisplayConfigTest {

    @Test
    fun `default config has expected values`() {
        val config = DisplayConfig()
        assertEquals(1920, config.width)
        assertEquals(1200, config.height)
        assertEquals(60, config.fps)
        assertEquals(DisplayConfig.Codec.HEVC, config.codec)
        assertEquals(20_000, config.bitrateKbps)
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
}
