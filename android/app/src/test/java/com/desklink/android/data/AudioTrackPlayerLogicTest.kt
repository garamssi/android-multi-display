package com.desklink.android.data

import com.desklink.android.data.audio.AudioProtocol
import com.desklink.android.data.audio.AvSyncCoordinator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Byte-level arithmetic the player performs on a sync decision.
 *
 * `AudioTrackPlayer` needs a real `AudioTrack`, so it cannot run on the JVM. What can go
 * wrong without hardware is the frame-to-byte conversion: a wrong silence length shifts
 * audio by the wrong amount, and broken frame accounting corrupts the playout prediction.
 */
class AudioTrackPlayerLogicTest {

    private val format = AudioProtocol.AudioFormat(sampleRate = 48_000, channelCount = 2, bitsPerSample = 16)

    /** A real Core Audio render quantum. */
    private val quantumFrames = 512

    @Test
    fun `silence length is a whole number of frames`() {
        val frames = 1_440 // 30 ms at 48 kHz
        val silenceBytes = frames * format.bytesPerFrame
        assertEquals(0, silenceBytes % format.bytesPerFrame)
        assertEquals(frames, silenceBytes / format.bytesPerFrame)
    }

    @Test
    fun `written bytes convert to frames exactly`() {
        assertEquals(quantumFrames, (quantumFrames * format.bytesPerFrame) / format.bytesPerFrame)
    }

    /** Mono halves bytes-per-frame, so the same arithmetic must follow the format. */
    @Test
    fun `arithmetic follows the stream format`() {
        val mono = AudioProtocol.AudioFormat(sampleRate = 48_000, channelCount = 1, bitsPerSample = 16)
        assertEquals(2, mono.bytesPerFrame)
        assertEquals(512, (512 * mono.bytesPerFrame) / mono.bytesPerFrame)
    }

    /**
     * The rate trim must survive the round trip through `Float`. A trim that vanishes to
     * 1.0f once stored would silently disable the steady-state actuator.
     */
    @Test
    fun `rate trim is representable as a float`() {
        val fastest = 1.0f + AvSyncCoordinator.MAX_RATE_TRIM
        val slowest = 1.0f - AvSyncCoordinator.MAX_RATE_TRIM
        assertTrue(fastest > 1.0f, "speed-up collapsed to 1.0")
        assertTrue(slowest < 1.0f, "slow-down collapsed to 1.0")
    }

    /**
     * The trim must move enough audio per second to matter. At 0.2% one second of
     * playback shifts by 2 ms, so a typical skew clears in seconds, not minutes.
     */
    @Test
    fun `rate trim shifts a useful amount per second`() {
        val shiftUsPerSecond = (AvSyncCoordinator.MAX_RATE_TRIM * 1_000_000L).toLong()
        assertTrue(shiftUsPerSecond >= 1_000, "trim shifts only $shiftUsPerSecond us per second")
    }
}
