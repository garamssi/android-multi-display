package com.desklink.android.data

import android.media.MediaCodec
import com.desklink.android.data.codec.HEVCDecoder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class HEVCDecoderTest {

    private fun mockCodecWithInputBuffers(): MediaCodec {
        val codec = mockk<MediaCodec>(relaxed = true)
        every { codec.getInputBuffer(any()) } answers { ByteBuffer.allocate(2 * 1024 * 1024) }
        return codec
    }

    @Test
    fun `input frames are not dropped when no input buffer is available`() {
        val codec = mockCodecWithInputBuffers()
        val dequeueResults = ArrayDeque(listOf(-1, -1, -1))
        var nextIndex = 0
        every { codec.dequeueInputBuffer(any()) } answers {
            if (dequeueResults.isNotEmpty()) dequeueResults.removeFirst() else nextIndex++
        }
        every { codec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val decoder = HEVCDecoder()
        decoder.attachCodecForTest(codec)

        decoder.submitFrame(byteArrayOf(1), 100, false)
        decoder.submitFrame(byteArrayOf(2), 200, false)
        decoder.submitFrame(byteArrayOf(3), 300, true)
        assertEquals(3, decoder.pendingFrameCount(), "frames must be buffered, not dropped")

        decoder.renderFrame()
        assertEquals(0, decoder.pendingFrameCount(), "all buffered frames must be flushed")

        verify(exactly = 3) { codec.queueInputBuffer(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `input is queued with flags zero, never BUFFER_FLAG_KEY_FRAME`() {
        val codec = mockCodecWithInputBuffers()
        var idx = 0
        every { codec.dequeueInputBuffer(any()) } answers { idx++ }
        every { codec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val flagsSlot = slot<Int>()
        every {
            codec.queueInputBuffer(any(), any(), any(), any(), capture(flagsSlot))
        } answers {}

        val decoder = HEVCDecoder()
        decoder.attachCodecForTest(codec)

        decoder.submitFrame(byteArrayOf(9), 1_000, isKeyframe = true)

        assertEquals(0, flagsSlot.captured, "decoder input flags must be 0")
    }

    @Test
    fun `renderFrame drains all ready output buffers in one call`() {
        val codec = mockCodecWithInputBuffers()
        every { codec.dequeueInputBuffer(any()) } returns -1 // nothing to input here

        val outputs = ArrayDeque(listOf(0, 1, 2, MediaCodec.INFO_TRY_AGAIN_LATER))
        every { codec.dequeueOutputBuffer(any(), any()) } answers { outputs.removeFirst() }

        val decoder = HEVCDecoder()
        decoder.attachCodecForTest(codec)

        val rendered = decoder.renderFrame()

        assertTrue(rendered, "should report at least one rendered frame")
        verify(exactly = 1) { codec.releaseOutputBuffer(0, true) }
        verify(exactly = 1) { codec.releaseOutputBuffer(1, true) }
        verify(exactly = 1) { codec.releaseOutputBuffer(2, true) }
    }

    @Test
    fun `renderFrame tolerates INFO_OUTPUT_BUFFERS_CHANGED and FORMAT_CHANGED`() {
        val codec = mockCodecWithInputBuffers()
        every { codec.dequeueInputBuffer(any()) } returns -1

        @Suppress("DEPRECATION")
        val buffersChanged = MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
        val outputs = ArrayDeque(
            listOf(
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                buffersChanged,
                5, // a real buffer after the info signals
                MediaCodec.INFO_TRY_AGAIN_LATER,
            ),
        )
        every { codec.dequeueOutputBuffer(any(), any()) } answers { outputs.removeFirst() }

        val decoder = HEVCDecoder()
        decoder.attachCodecForTest(codec)

        val rendered = decoder.renderFrame()

        assertTrue(rendered)
        verify(exactly = 1) { codec.releaseOutputBuffer(5, true) }
    }

    @Test
    fun `submitFrame is a no-op before configure`() {
        val decoder = HEVCDecoder()
        decoder.submitFrame(byteArrayOf(1, 2, 3), 0, false)
        assertEquals(0, decoder.pendingFrameCount())
    }

    @Test
    fun `release still releases the codec when stop throws`() = runTest {
        val codec = mockk<MediaCodec>(relaxed = true)
        every { codec.stop() } throws IllegalStateException("error state")
        val decoder = HEVCDecoder()
        decoder.attachCodecForTest(codec)

        decoder.release() // must not throw

        verify(exactly = 1) { codec.release() }
    }
}
