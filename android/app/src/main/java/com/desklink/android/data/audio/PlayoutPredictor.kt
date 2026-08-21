package com.desklink.android.data.audio

// Predicts when the next sample frame written to AudioTrack will actually be heard.
//
// Lip-sync needs the real playout time, not "now": whatever is already queued plays
// first, and at 48 kHz that is tens of milliseconds. Treating a write as instant makes
// AvSyncCoordinator believe the audio is running ahead and correct for skew that is not
// there.
//
// Pure arithmetic: no Android APIs, no clock of its own.
class PlayoutPredictor(
    private val format: AudioProtocol.AudioFormat,
) {

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }

    // Playout time of frame [framesWritten], given that [reportedFramePosition] was heard
    // at [reportedNanos] and the track is running at [playbackSpeed].
    //
    // The result is deliberately NOT floored at the current time. Such a clamp is
    // one-sided: every time it engages it moves the error in one direction only, turning
    // symmetric measurement noise into a standing skew the coordinator chases forever.
    fun predictFromTimestamp(
        framesWritten: Long,
        reportedFramePosition: Long,
        reportedNanos: Long,
        playbackSpeed: Float,
    ): Long {
        val queuedFrames = (framesWritten - reportedFramePosition).coerceAtLeast(0L)
        return reportedNanos + framesToNanos(queuedFrames, playbackSpeed)
    }

    // Duration of [frames] sample frames at [speed], in nanoseconds.
    //
    // Divides before multiplying: frames * NANOS_PER_SECOND overflows a Long after about
    // six hours at 48 kHz, and this runs for the whole session.
    private fun framesToNanos(frames: Long, speed: Float): Long {
        // A non-positive speed cannot describe playback; fall back to real time rather
        // than dividing by zero or running time backwards.
        val effectiveRate = if (speed > 0f) format.sampleRate * speed.toDouble() else format.sampleRate.toDouble()
        val wholeSeconds = (frames / effectiveRate).toLong()
        val remainderFrames = frames - (wholeSeconds * effectiveRate).toLong()
        return wholeSeconds * NANOS_PER_SECOND + (remainderFrames * NANOS_PER_SECOND / effectiveRate).toLong()
    }
}
