package com.desklink.android.data

import com.desklink.android.data.codec.VideoProtocol
import com.desklink.android.domain.model.DisplayConfig
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VideoProtocolTest {

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `parse VIDEO_CONFIG golden vector`() {
        val payload = hexToBytes("01" + "0005" + "0000000140")
        val cfg = VideoProtocol.parseConfig(payload)!!
        assertEquals(DisplayConfig.Codec.HEVC, cfg.codec)
        assertArrayEquals(hexToBytes("0000000140"), cfg.csd)
    }

    @Test
    fun `parse VIDEO_CONFIG rejects overrun length`() {
        val payload = hexToBytes("01" + "00FF")
        assertNull(VideoProtocol.parseConfig(payload))
    }

    @Test
    fun `parse VIDEO_CONFIG rejects unknown codec`() {
        val payload = hexToBytes("09" + "0001" + "40")
        assertNull(VideoProtocol.parseConfig(payload))
    }

    @Test
    fun `parse VIDEO_FRAME golden vector`() {
        val payload = hexToBytes(
            "00000000000F4240" + // ts int64 BE
                "01" +               // flags: keyframe
                "0000002A" +         // frame number 42
                "000000012600",      // Annex-B NAL
        )
        val frame = VideoProtocol.parseFrame(payload)!!
        assertEquals(1_000_000L, frame.timestampUs)
        assertTrue(frame.isKeyframe)
        assertFalse(frame.isConfig)
        assertEquals(42L, frame.frameNumber)
        assertArrayEquals(hexToBytes("000000012600"), frame.nal)
    }

    @Test
    fun `parse VIDEO_FRAME config flag`() {
        val payload = hexToBytes(
            "0000000000000000" + // ts 0
                "02" +               // flags: config (bit1)
                "00000001" +         // frame 1
                "00000001420100",    // some CSD-carrying NAL
        )
        val frame = VideoProtocol.parseFrame(payload)!!
        assertFalse(frame.isKeyframe)
        assertTrue(frame.isConfig)
    }

    @Test
    fun `parse VIDEO_FRAME rejects short payload`() {
        assertNull(VideoProtocol.parseFrame(ByteArray(12)))
    }

    @Test
    fun `parse VIDEO_FRAME allows empty NAL`() {
        val payload = hexToBytes("00000000000F4240" + "00" + "0000002A")
        val frame = VideoProtocol.parseFrame(payload)!!
        assertEquals(0, frame.nal.size)
    }
}
