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

/**
 * A-C3 / A-M1 decoder state tests using a mocked [MediaCodec]:
 *  - input frames are buffered/retried, never silently dropped when no input
 *    buffer is available;
 *  - renderFrame drains ALL ready output buffers per call (not one-per-vsync);
 *  - no BUFFER_FLAG_KEY_FRAME is passed on input (flags == 0 for frames).
 */
class HEVCDecoderTest {

    private fun mockCodecWithInputBuffers(): MediaCodec {
        val codec = mockk<MediaCodec>(relaxed = true)
        // getInputBuffer returns a fresh buffer for any index.
        every { codec.getInputBuffer(any()) } answers { ByteBuffer.allocate(2 * 1024 * 1024) }
        return codec
    }

    @Test
    fun `input frames are not dropped when no input buffer is available`() {
        val codec = mockCodecWithInputBuffers()
        // First 3 dequeue attempts: no buffer available (-1); afterwards: buffers 0..n.
        val dequeueResults = ArrayDeque(listOf(-1, -1, -1))
        var nextIndex = 0
        every { codec.dequeueInputBuffer(any()) } answers {
            if (dequeueResults.isNotEmpty()) dequeueResults.removeFirst() else nextIndex++
        }
        // No output ready.
        every { codec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val decoder = HEVCDecoder()
        decoder.attachCodecForTest(codec)

        // Submit 3 frames while no input buffer is available -> all should be queued
        // internally, none dropped.
        decoder.submitFrame(byteArrayOf(1), 100, false)
        decoder.submitFrame(byteArrayOf(2), 200, false)
        decoder.submitFrame(byteArrayOf(3), 300, true)
        assertEquals(3, decoder.pendingFrameCount(), "frames must be buffered, not dropped")

        // Now buffers become available; a render pump should flush all 3.
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

        // Even a keyframe must be queued with flags = 0.
        decoder.submitFrame(byteArrayOf(9), 1_000, isKeyframe = true)

        assertEquals(0, flagsSlot.captured, "decoder input flags must be 0")
    }

    @Test
    fun `renderFrame drains all ready output buffers in one call`() {
        val codec = mockCodecWithInputBuffers()
        every { codec.dequeueInputBuffer(any()) } returns -1 // nothing to input here

        // 3 ready output buffers, then TRY_AGAIN_LATER.
        val outputs = ArrayDeque(listOf(0, 1, 2, MediaCodec.INFO_TRY_AGAIN_LATER))
        every { codec.dequeueOutputBuffer(any(), any()) } answers { outputs.removeFirst() }

        val decoder = HEVCDecoder()
        decoder.attachCodecForTest(codec)

        val rendered = decoder.renderFrame()

        assertTrue(rendered, "should report at least one rendered frame")
        // All three output buffers released+rendered in a single renderFrame call.
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
        // No codec attached.
        decoder.submitFrame(byteArrayOf(1, 2, 3), 0, false)
        assertEquals(0, decoder.pendingFrameCount())
    }

    @Test
    fun `release still releases the codec when stop throws`() = runTest {
        val codec = mockk<MediaCodec>(relaxed = true)
        // A codec in an error state throws on stop(); release() must still run.
        every { codec.stop() } throws IllegalStateException("error state")
        val decoder = HEVCDecoder()
        decoder.attachCodecForTest(codec)

        decoder.release() // must not throw

        verify(exactly = 1) { codec.release() }
    }
}
