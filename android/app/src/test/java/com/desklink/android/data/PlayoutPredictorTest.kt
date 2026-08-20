package com.desklink.android.data

import com.desklink.android.data.audio.AudioProtocol
import com.desklink.android.data.audio.PlayoutPredictor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Predicts when the next sample frame handed to `AudioTrack` will actually be heard.
 *
 * This is the other half of lip-sync: the coordinator can only steer audio if it knows
 * the real playout time, and "now" is wrong by however much sits buffered in the track.
 * `AudioTrack.getTimestamp` reports that a specific frame position was played at a
 * specific nanotime; everything still queued behind it plays later, at the sample rate.
 */
class PlayoutPredictorTest {

    private val format = AudioProtocol.AudioFormat(sampleRate = 48_000, channelCount = 2, bitsPerSample = 16)
    private val predictor = PlayoutPredictor(format)

    private val nanosPerSecond = 1_000_000_000L

    /**
     * With 48000 frames (one second) still queued behind the last reported position, the
     * next frame written is heard one second after that report.
     */
    @Test
    fun `predicts playout from the reported frame position`() {
        val predicted = predictor.predictFromTimestamp(
            framesWritten = 96_000L,
            reportedFramePosition = 48_000L,
            reportedNanos = 10 * nanosPerSecond,
            nowNanos = 10 * nanosPerSecond,
        )
        assertEquals(11 * nanosPerSecond, predicted)
    }

    @Test
    fun `predicts immediate playout when the track has drained`() {
        val predicted = predictor.predictFromTimestamp(
            framesWritten = 48_000L,
            reportedFramePosition = 48_000L,
            reportedNanos = 10 * nanosPerSecond,
            nowNanos = 10 * nanosPerSecond,
        )
        assertEquals(10 * nanosPerSecond, predicted)
    }

    @Test
    fun `scales queued frames by the sample rate`() {
        val slowFormat = AudioProtocol.AudioFormat(sampleRate = 24_000, channelCount = 2, bitsPerSample = 16)
        val predicted = PlayoutPredictor(slowFormat).predictFromTimestamp(
            framesWritten = 24_000L,
            reportedFramePosition = 0L,
            reportedNanos = 0L,
            nowNanos = 0L,
        )
        // 24000 frames at 24 kHz is one second, not half of one.
        assertEquals(nanosPerSecond, predicted)
    }

    /**
     * A stale timestamp report can put the predicted playout in the past. That is not a
     * usable prediction — audio cannot be heard before now — so it is floored at now.
     */
    @Test
    fun `never predicts a playout time in the past`() {
        val predicted = predictor.predictFromTimestamp(
            framesWritten = 48_000L,
            reportedFramePosition = 48_000L,
            reportedNanos = 5 * nanosPerSecond,
            nowNanos = 10 * nanosPerSecond,
        )
        assertEquals(10 * nanosPerSecond, predicted)
    }

    /**
     * `AudioTrack.getTimestamp` returns nothing until enough audio has played. Until then
     * the estimate must come from what has been written and what has been consumed,
     * rather than pretending playout is instant — which would make the coordinator think
     * audio is far ahead and pad silence that is not needed.
     */
    @Test
    fun `falls back to a buffer estimate before the first timestamp`() {
        val predicted = predictor.predictFromBufferFill(
            framesWritten = 48_000L,
            framesConsumed = 0L,
            nowNanos = 10 * nanosPerSecond,
        )
        assertEquals(11 * nanosPerSecond, predicted)
    }

    @Test
    fun `buffer estimate shrinks as frames are consumed`() {
        val predicted = predictor.predictFromBufferFill(
            framesWritten = 48_000L,
            framesConsumed = 24_000L,
            nowNanos = 10 * nanosPerSecond,
        )
        assertEquals(10 * nanosPerSecond + nanosPerSecond / 2, predicted)
    }

    @Test
    fun `buffer estimate is never negative`() {
        val predicted = predictor.predictFromBufferFill(
            framesWritten = 0L,
            framesConsumed = 48_000L,
            nowNanos = 10 * nanosPerSecond,
        )
        assertEquals(10 * nanosPerSecond, predicted)
    }

    /**
     * Long sessions must not overflow: at 48 kHz a Long frame count is effectively
     * unbounded, but the intermediate `frames * 1e9` is where a naive implementation
     * overflows after about six hours.
     */
    @Test
    fun `does not overflow on a long session`() {
        val sixHoursFrames = 6L * 3_600L * 48_000L
        val predicted = predictor.predictFromTimestamp(
            framesWritten = sixHoursFrames + 48_000L,
            reportedFramePosition = sixHoursFrames,
            reportedNanos = 6L * 3_600L * nanosPerSecond,
            nowNanos = 6L * 3_600L * nanosPerSecond,
        )
        assertTrue(predicted > 0, "predicted playout overflowed to $predicted")
        assertEquals(6L * 3_600L * nanosPerSecond + nanosPerSecond, predicted)
    }
}
