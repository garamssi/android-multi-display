package com.desklink.android.data

import com.desklink.android.data.audio.AudioProtocol
import com.desklink.android.domain.model.MessageType
import com.desklink.android.domain.model.ProtocolConstants
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Audio-channel payload parsing (AUDIO_CONFIG 0x30, AUDIO_FRAME 0x31).
 *
 * Golden vectors are shared with `tools/protocol_vectors.py` and the macOS tests, so a
 * divergence on either side shows up here.
 */
class AudioProtocolTest {

    @Test
    fun `parses AUDIO_CONFIG golden vector`() {
        // 48000 Hz, 2 ch, 16 bits, PCM_S16LE -> 0000BB80 02 10 01
        val payload = byteArrayOf(0x00, 0x00, 0xBB.toByte(), 0x80.toByte(), 0x02, 0x10, 0x01)
        val format = AudioProtocol.parseConfig(payload)!!
        assertEquals(48_000, format.sampleRate)
        assertEquals(2, format.channelCount)
        assertEquals(16, format.bitsPerSample)
        assertEquals(4, format.bytesPerFrame)
    }

    @Test
    fun `rejects short AUDIO_CONFIG`() {
        assertNull(AudioProtocol.parseConfig(byteArrayOf(0x00, 0x00, 0xBB.toByte(), 0x80.toByte(), 0x02, 0x10)))
    }

    @Test
    fun `rejects unknown encoding`() {
        assertNull(
            AudioProtocol.parseConfig(
                byteArrayOf(0x00, 0x00, 0xBB.toByte(), 0x80.toByte(), 0x02, 0x10, 0xFF.toByte())
            )
        )
    }

    /** Zero channels would make bytesPerFrame zero and divide by zero downstream. */
    @Test
    fun `rejects zero channel count`() {
        assertNull(
            AudioProtocol.parseConfig(
                byteArrayOf(0x00, 0x00, 0xBB.toByte(), 0x80.toByte(), 0x00, 0x10, 0x01)
            )
        )
    }

    @Test
    fun `rejects zero sample rate`() {
        assertNull(AudioProtocol.parseConfig(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x02, 0x10, 0x01)))
    }

    /** A bit depth that contradicts the encoding would frame every sample wrongly. */
    @Test
    fun `rejects bit depth contradicting encoding`() {
        assertNull(
            AudioProtocol.parseConfig(
                byteArrayOf(0x00, 0x00, 0xBB.toByte(), 0x80.toByte(), 0x02, 0x18, 0x01)
            )
        )
    }

    @Test
    fun `parses AUDIO_FRAME golden vector`() {
        // ts 1_000_000 = 00000000000F4240, frameCount 2 = 00000002, then 8 PCM bytes
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x0F, 0x42, 0x40) +
            byteArrayOf(0x00, 0x00, 0x00, 0x02) + pcm

        val chunk = AudioProtocol.parseFrame(payload)!!
        assertEquals(1_000_000L, chunk.timestampUs)
        assertEquals(2, chunk.frameCount)
        assertArrayEquals(pcm, chunk.pcm)
    }

    /** Timestamps are host-uptime microseconds and must survive as signed values. */
    @Test
    fun `parses negative timestamp`() {
        val payload = ByteArray(8) { 0xFF.toByte() } +
            byteArrayOf(0x00, 0x00, 0x00, 0x01) + byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        assertEquals(-1L, AudioProtocol.parseFrame(payload)!!.timestampUs)
    }

    @Test
    fun `rejects truncated AUDIO_FRAME header`() {
        assertNull(AudioProtocol.parseFrame(ByteArray(AudioProtocol.FRAME_HEADER_SIZE - 1)))
    }

    /** Framing is legal but there is no audio, so it must not enter the sync path. */
    @Test
    fun `rejects AUDIO_FRAME with no PCM`() {
        assertNull(AudioProtocol.parseFrame(ByteArray(AudioProtocol.FRAME_HEADER_SIZE)))
    }

    /**
     * A frame whose declared frameCount disagrees with its PCM could claim any duration
     * it liked and skew the playout prediction lip-sync depends on.
     */
    @Test
    fun `detects frame count inconsistent with pcm length`() {
        val format = AudioProtocol.parseConfig(
            byteArrayOf(0x00, 0x00, 0xBB.toByte(), 0x80.toByte(), 0x02, 0x10, 0x01)
        )!!
        val header = ByteArray(8) + byteArrayOf(0x00, 0x00, 0x00, 0x02)

        val exact = AudioProtocol.parseFrame(header + ByteArray(8))!!
        assertTrue(exact.isConsistentWith(format))

        val overstated = AudioProtocol.parseFrame(header + ByteArray(4))!!
        assertTrue(!overstated.isConsistentWith(format))
    }

    /**
     * A frame count that cannot describe the payload must be refused at parse time.
     * Read as a signed Int a large uint32 arrives negative, and a huge positive value
     * overflowed `frameCount * bytesPerFrame` so the consistency check passed — letting a
     * 17-byte frame claim a billion sample frames, which defeated the per-chunk
     * correction cap and triggered a multi-second blocking write of silence.
     */
    @Test
    fun `rejects a frame count that cannot describe the payload`() {
        val header = { count: Int ->
            ByteArray(8) + byteArrayOf(
                (count ushr 24).toByte(), (count ushr 16).toByte(),
                (count ushr 8).toByte(), count.toByte(),
            )
        }
        // 0x40000001 overflows Int when multiplied by bytesPerFrame.
        assertNull(AudioProtocol.parseFrame(header(0x4000_0001) + ByteArray(4)))
        // Negative once read as a signed Int.
        assertNull(AudioProtocol.parseFrame(header(-1) + ByteArray(4)))
        assertNull(AudioProtocol.parseFrame(header(0) + ByteArray(4)))
        // More frames than there are BYTES cannot be right for any format.
        assertNull(AudioProtocol.parseFrame(header(5) + ByteArray(4)))
    }

    @Test
    fun `accepts a frame count that matches the payload`() {
        val header = ByteArray(8) + byteArrayOf(0x00, 0x00, 0x00, 0x02)
        val chunk = AudioProtocol.parseFrame(header + ByteArray(8))
        assertEquals(2, chunk!!.frameCount)
    }

    @Test
    fun `audio message types match spec`() {
        assertEquals(0x30.toByte(), MessageType.AUDIO_CONFIG)
        assertEquals(0x31.toByte(), MessageType.AUDIO_FRAME)
    }

    @Test
    fun `audio port follows input port`() {
        assertEquals(ProtocolConstants.PORT_INPUT + 1, ProtocolConstants.PORT_AUDIO)
        assertEquals(7103, ProtocolConstants.PORT_AUDIO)
    }

    /** Duration must come from the sample rate, not be assumed 48 kHz. */
    @Test
    fun `frame count converts to duration`() {
        val format = AudioProtocol.parseConfig(
            byteArrayOf(0x00, 0x00, 0xBB.toByte(), 0x80.toByte(), 0x02, 0x10, 0x01)
        )!!
        assertEquals(1_000_000L, format.durationUs(48_000))
        assertEquals(10_000L, format.durationUs(480))
    }
}
