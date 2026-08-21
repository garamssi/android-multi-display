package com.desklink.android.data

import com.desklink.android.data.audio.AudioProtocol
import com.desklink.android.data.audio.PlayoutPredictor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Predicts when the next sample frame handed to `AudioTrack` will actually be heard.
 *
 * Lip-sync needs the real playout time, not "now": whatever is queued plays first.
 * `AudioTrack.getTimestamp` reports that a given frame position was heard at a given
 * nanotime; everything behind it plays later, at the sample rate scaled by the current
 * playback speed.
 */
class PlayoutPredictorTest {

    private val format = AudioProtocol.AudioFormat(sampleRate = 48_000, channelCount = 2, bitsPerSample = 16)
    private val predictor = PlayoutPredictor(format)
    private val nanosPerSecond = 1_000_000_000L

    @Test
    fun `predicts playout from the reported frame position`() {
        val predicted = predictor.predictFromTimestamp(
            framesWritten = 96_000L,
            reportedFramePosition = 48_000L,
            reportedNanos = 10 * nanosPerSecond,
            playbackSpeed = 1.0f,
        )
        assertEquals(11 * nanosPerSecond, predicted)
    }

    @Test
    fun `predicts immediate playout when the track has drained`() {
        val predicted = predictor.predictFromTimestamp(
            framesWritten = 48_000L,
            reportedFramePosition = 48_000L,
            reportedNanos = 10 * nanosPerSecond,
            playbackSpeed = 1.0f,
        )
        assertEquals(10 * nanosPerSecond, predicted)
    }

    @Test
    fun `scales queued frames by the sample rate`() {
        val slow = AudioProtocol.AudioFormat(sampleRate = 24_000, channelCount = 2, bitsPerSample = 16)
        val predicted = PlayoutPredictor(slow).predictFromTimestamp(
            framesWritten = 24_000L,
            reportedFramePosition = 0L,
            reportedNanos = 0L,
            playbackSpeed = 1.0f,
        )
        assertEquals(nanosPerSecond, predicted)
    }

    /**
     * A rate trim changes how fast the queue drains, so it changes when the next frame is
     * heard. Ignoring it would make the prediction wrong by exactly the amount the
     * coordinator is trying to correct, which is a feedback loop fighting itself.
     */
    @Test
    fun `accounts for playback speed`() {
        val faster = predictor.predictFromTimestamp(
            framesWritten = 48_000L,
            reportedFramePosition = 0L,
            reportedNanos = 0L,
            playbackSpeed = 1.002f,
        )
        val normal = predictor.predictFromTimestamp(
            framesWritten = 48_000L,
            reportedFramePosition = 0L,
            reportedNanos = 0L,
            playbackSpeed = 1.0f,
        )
        assertTrue(faster < normal, "playing faster must drain the queue sooner")

        val slower = predictor.predictFromTimestamp(
            framesWritten = 48_000L,
            reportedFramePosition = 0L,
            reportedNanos = 0L,
            playbackSpeed = 0.998f,
        )
        assertTrue(slower > normal, "playing slower must drain the queue later")
    }

    /** A nonsensical speed must not divide by zero or invert time. */
    @Test
    fun `guards against a non positive speed`() {
        val predicted = predictor.predictFromTimestamp(
            framesWritten = 48_000L,
            reportedFramePosition = 0L,
            reportedNanos = 0L,
            playbackSpeed = 0f,
        )
        assertEquals(nanosPerSecond, predicted, "a zero speed must fall back to real time")
    }

    /**
     * The result must NOT be floored at "now". Clamping is one-sided: whenever it engages
     * it biases the error in a single direction, rectifying symmetric noise into a
     * standing skew that the coordinator then corrects forever.
     */
    @Test
    fun `does not clamp a stale prediction to now`() {
        val predicted = predictor.predictFromTimestamp(
            framesWritten = 48_000L,
            reportedFramePosition = 48_000L,
            reportedNanos = 5 * nanosPerSecond,
            playbackSpeed = 1.0f,
        )
        assertEquals(5 * nanosPerSecond, predicted, "prediction was clamped instead of reported as-is")
    }

    /**
     * Long sessions must not overflow: `frames * 1e9` overflows a Long after about six
     * hours at 48 kHz, and this runs for the whole session.
     */
    @Test
    fun `does not overflow on a long session`() {
        val sixHoursFrames = 6L * 3_600L * 48_000L
        val predicted = predictor.predictFromTimestamp(
            framesWritten = sixHoursFrames + 48_000L,
            reportedFramePosition = sixHoursFrames,
            reportedNanos = 6L * 3_600L * nanosPerSecond,
            playbackSpeed = 1.0f,
        )
        assertTrue(predicted > 0, "predicted playout overflowed to $predicted")
        assertEquals(6L * 3_600L * nanosPerSecond + nanosPerSecond, predicted)
    }

    /** A negative queue (a stale report) contributes nothing rather than going backwards. */
    @Test
    fun `treats a negative queue as empty`() {
        val predicted = predictor.predictFromTimestamp(
            framesWritten = 1_000L,
            reportedFramePosition = 48_000L,
            reportedNanos = 10 * nanosPerSecond,
            playbackSpeed = 1.0f,
        )
        assertEquals(10 * nanosPerSecond, predicted)
    }
}
