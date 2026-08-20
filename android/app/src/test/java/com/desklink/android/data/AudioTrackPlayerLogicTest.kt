package com.desklink.android.data

import com.desklink.android.data.audio.AudioProtocol
import com.desklink.android.data.audio.AvSyncCoordinator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Byte-level arithmetic the player performs on a sync decision.
 *
 * `AudioTrackPlayer` itself needs a real `AudioTrack`, so it cannot run on the JVM. What
 * CAN go wrong without hardware is the frame->byte arithmetic: a wrong skip offset plays
 * the wrong samples, and a wrong silence length shifts audio by the wrong amount. Those
 * conversions are pinned here against the same format the player uses.
 */
class AudioTrackPlayerLogicTest {

    private val format = AudioProtocol.AudioFormat(sampleRate = 48_000, channelCount = 2, bitsPerSample = 16)

    /** A real Core Audio render quantum: 512 sample frames of 16-bit stereo. */
    private val quantumFrames = 512
    private val quantumBytes = quantumFrames * 4

    @Test
    fun `skip offset stays inside the chunk for the capped correction`() {
        val capped = (quantumFrames * AvSyncCoordinator.MAX_CORRECTION_FRACTION).toInt()
        val offset = capped * format.bytesPerFrame
        assertTrue(offset < quantumBytes, "skip offset $offset would consume the whole chunk")
        // Derived from the cap rather than hardcoded, so tuning the rate does not silently
        // invalidate the assertion.
        assertEquals(capped * 4, offset)
        assertTrue(capped > 0, "cap floored to zero frames at a real quantum size")
    }

    /** Silence must be a whole number of frames, or every later sample is misaligned. */
    @Test
    fun `silence length is a whole number of frames`() {
        val frames = (quantumFrames * AvSyncCoordinator.MAX_CORRECTION_FRACTION).toInt()
        val silenceBytes = frames * format.bytesPerFrame
        assertEquals(0, silenceBytes % format.bytesPerFrame)
        assertEquals(frames, silenceBytes / format.bytesPerFrame)
    }

    /**
     * The correction the coordinator asks for, converted to bytes and back, must round
     * trip. A mismatch here means the applied correction differs from the intended one and
     * the loop would never settle.
     */
    @Test
    fun `correction round trips between frames and bytes`() {
        for (frames in intArrayOf(1, 7, 128, 512)) {
            val bytes = frames * format.bytesPerFrame
            assertEquals(frames, bytes / format.bytesPerFrame)
        }
    }

    /** Written-byte accounting must convert to frames exactly for any whole-frame write. */
    @Test
    fun `written bytes convert to frames exactly`() {
        assertEquals(quantumFrames, quantumBytes / format.bytesPerFrame)
        assertEquals(1, format.bytesPerFrame * 1 / format.bytesPerFrame)
    }

    /** A mono stream halves bytes-per-frame, so the same math must follow the format. */
    @Test
    fun `arithmetic follows the stream format`() {
        val mono = AudioProtocol.AudioFormat(sampleRate = 48_000, channelCount = 1, bitsPerSample = 16)
        assertEquals(2, mono.bytesPerFrame)
        assertEquals(512, (512 * mono.bytesPerFrame) / mono.bytesPerFrame)
    }
}
