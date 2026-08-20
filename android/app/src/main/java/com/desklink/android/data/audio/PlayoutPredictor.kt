package com.desklink.android.data.audio

/**
 * Predicts when the next sample frame written to `AudioTrack` will actually be heard.
 *
 * Lip-sync needs the real playout time, not "now": whatever is already queued in the
 * track plays first, and at 48 kHz a buffer can hold tens of milliseconds. Treating the
 * write as instant would make [AvSyncCoordinator] believe the audio is running ahead and
 * pad silence that is not needed.
 *
 * Two estimates, because `AudioTrack.getTimestamp` reports nothing until enough audio has
 * played: the precise one derived from a reported frame position, and a fallback derived
 * from how much has been written versus consumed.
 *
 * Pure arithmetic — no Android APIs, no clock of its own.
 */
class PlayoutPredictor(
    private val format: AudioProtocol.AudioFormat,
) {

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }

    /**
     * Playout time of frame [framesWritten], given that [reportedFramePosition] was heard
     * at [reportedNanos].
     *
     * Floored at [nowNanos]: a stale report can put the result in the past, and audio
     * cannot be heard before now, so such a value is not a usable prediction.
     */
    fun predictFromTimestamp(
        framesWritten: Long,
        reportedFramePosition: Long,
        reportedNanos: Long,
        nowNanos: Long,
    ): Long {
        val queuedFrames = (framesWritten - reportedFramePosition).coerceAtLeast(0L)
        return (reportedNanos + framesToNanos(queuedFrames)).coerceAtLeast(nowNanos)
    }

    /**
     * Playout time estimated from buffer fill, for use before the first timestamp report.
     */
    fun predictFromBufferFill(framesWritten: Long, framesConsumed: Long, nowNanos: Long): Long {
        val queuedFrames = (framesWritten - framesConsumed).coerceAtLeast(0L)
        return nowNanos + framesToNanos(queuedFrames)
    }

    /**
     * Duration of [frames] sample frames in nanoseconds.
     *
     * Divides before multiplying where possible: `frames * NANOS_PER_SECOND` overflows a
     * Long after about six hours at 48 kHz, and this runs for the whole session.
     */
    private fun framesToNanos(frames: Long): Long {
        val wholeSeconds = frames / format.sampleRate
        val remainder = frames % format.sampleRate
        return wholeSeconds * NANOS_PER_SECOND + remainder * NANOS_PER_SECOND / format.sampleRate
    }
}
