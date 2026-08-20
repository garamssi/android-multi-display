package com.desklink.android.data.audio

import kotlin.math.abs

/**
 * Keeps audio aligned with the picture.
 *
 * **Why video is the master clock.** The video path renders decoded frames on arrival,
 * driven by vsync with no presentation-time scheduling, because low latency is the entire
 * point of a screen mirror. Delaying the picture to match audio would trade away that
 * latency, so audio is steered onto the video instead.
 *
 * **How.** Server timestamps for video and audio share one axis (host uptime microseconds
 * on the Mac — see the protocol spec), so the offset between server time and this
 * device's clock, learned from frames as they are rendered, tells us when a given audio
 * chunk *should* be heard. Comparing that against when `AudioTrack` will actually play it
 * yields a skew, and the skew decides whether to play, nudge earlier by dropping frames,
 * or delay by inserting silence.
 *
 * **Why the thresholds are asymmetric.** Audio arriving ahead of the picture is noticed at
 * a much smaller offset than audio arriving behind it, so leading audio is corrected
 * sooner than trailing audio.
 *
 * This class is pure arithmetic: no Android APIs, no time source of its own. Everything
 * it needs is passed in, which is what makes the policy testable.
 */
class AvSyncCoordinator(
    private val format: AudioProtocol.AudioFormat,
) {

    companion object {
        /**
         * Skew tolerated when audio would play AHEAD of the picture. Kept tight: leading
         * audio is the direction viewers detect first.
         */
        const val AHEAD_TOLERANCE_US = 25_000L

        /** Skew tolerated when audio would play BEHIND the picture. */
        const val BEHIND_TOLERANCE_US = 60_000L

        /**
         * Delay between a frame being released to the Surface and actually being seen.
         *
         * `releaseOutputBuffer(index, true)` queues to SurfaceFlinger; it does not display.
         * The picture appears at least one vsync later (16.7 ms at 60 Hz). The audio side,
         * by contrast, reports true device presentation time via `AudioTrack.getTimestamp`.
         * Without this term the learned reference understates video latency by a vsync and
         * audio plays permanently early — in the direction [AHEAD_TOLERANCE_US] is
         * tightest, so the bias would sit just inside tolerance and never be corrected.
         *
         * One vsync at 60 Hz is used rather than the panel's real refresh rate: this class
         * is deliberately free of Android APIs, and the residual error on a faster panel is
         * a few milliseconds, well inside the tolerances above.
         */
        const val DISPLAY_PRESENTATION_DELAY_US = 16_667L

        /**
         * Past this skew, nudging one chunk at a time cannot catch up (the source was
         * paused, the link stalled). Discarding the stale chunk resynchronizes at once,
         * which beats a long stretch of audibly wrong audio. Applied in BOTH directions:
         * padding an unrecoverably early chunk instead would inject silence for as long as
         * the gap lasts, at the correction rate.
         */
        const val UNRECOVERABLE_SKEW_US = 400_000L

        /**
         * Largest share of a single chunk that may be skipped or padded ONCE the stream is
         * aligned.
         *
         * Every adjustment is a splice, not a resample, so it is audible as a faint tick.
         * In steady state the only thing left to correct is clock drift — about 41 ppm
         * (see the protocol spec) — so the rate only has to beat that. Chunks arrive in
         * real time, making this the catch-up rate directly: 5% is 50 ms of skew per
         * second, over a thousand times the drift, while keeping each splice well under a
         * millisecond at a 512-frame quantum.
         *
         * This was 25%, which paid off the startup skew in dozens of audible splices. That
         * skew is now handled by the one-shot alignment below instead.
         */
        const val MAX_CORRECTION_FRACTION = 0.05

        /**
         * Weight of each new observation in the offset estimate. Low enough that ordinary
         * frame jitter barely moves the reference, high enough that a sustained change in
         * transport delay is followed within a second of frames.
         */
        private const val OFFSET_SMOOTHING = 0.05

        /**
         * Deviation past which a single observation is treated as an outlier rather than
         * new information.
         *
         * This exists because of a specific, measured failure: `renderFrame` drains EVERY
         * ready output buffer in one vsync, reporting each with the same local time, so a
         * decoder that was briefly stalled reports a whole backlog as if it had rendered
         * instantaneously. Feeding those to the estimator moved a true 50 ms offset to
         * 204 ms and produced roughly a second of corrections against audio that was
         * already in sync. Ordinary jitter is tens of milliseconds, so 150 ms separates
         * noise from nonsense.
         */
        private const val OUTLIER_THRESHOLD_US = 150_000.0

        /**
         * Consecutive outlying observations after which the deviation is accepted as a
         * genuine change. Without this a real shift in transport delay (a slower link, a
         * different encoder setting) would be rejected forever. Ten frames is about a
         * sixth of a second at 60 fps.
         */
        private const val OUTLIER_TOLERANCE_FRAMES = 10

        private const val NANOS_PER_MICRO = 1_000L
        private const val MICROS_PER_SECOND = 1_000_000L
    }

    /** What to do with one audio chunk. */
    sealed interface Decision {
        /** Write the chunk unchanged. */
        data object Play : Decision

        /** Drop [frames] leading sample frames to pull the audio earlier. */
        data class SkipFrames(val frames: Int) : Decision

        /** Write [frames] sample frames of silence first to push the audio later. */
        data class InsertSilence(val frames: Int) : Decision

        /** Throw the chunk away; it is too far out of sync to be worth playing. */
        data object Discard : Decision
    }

    /**
     * Smoothed local-minus-server offset in microseconds, including
     * [DISPLAY_PRESENTATION_DELAY_US]; null until a frame is seen.
     *
     * Volatile because frames are reported from the render thread while chunks are decided
     * on the audio thread.
     */
    @Volatile
    private var offsetUs: Double? = null

    @Volatile
    private var consecutiveOutliers = 0

    /**
     * Local time of the last accepted observation.
     *
     * Frames drained in one vsync all carry the SAME local time, and only the newest of
     * them is the picture actually on screen — the rest are not independent observations
     * of anything. Sharing a local time is what distinguishes a drained backlog from a
     * genuine change in delay, so duplicates are dropped outright rather than being
     * counted as outliers (a long enough burst would otherwise exhaust
     * [OUTLIER_TOLERANCE_FRAMES] and be adopted as if it were real).
     */
    @Volatile
    private var lastObservedLocalNanos = Long.MIN_VALUE

    /**
     * False until the stream has been brought into sync once.
     *
     * `AudioTrack.getTimestamp` reports nothing until enough audio has played, so the
     * first chunks go out with no correction and a startup skew accumulates. Paying that
     * off at [MAX_CORRECTION_FRACTION] meant a long run of splices right after connecting
     * — measured at 22 corrections in a single session, each an audible tick, which is
     * exactly the crackle heard on connect. So the first alignment is done in one step:
     * at that point playback has barely begun, and a single large adjustment is inaudible
     * where two dozen small ones are not.
     */
    private var isAligned = false

    /**
     * Records that a video frame stamped [serverTimestampUs] was released to the Surface
     * at [localNanos] on this device's clock.
     */
    fun noteVideoRendered(serverTimestampUs: Long, localNanos: Long) {
        if (localNanos == lastObservedLocalNanos) return
        lastObservedLocalNanos = localNanos

        // The frame is SEEN a vsync after it is released, and that is the instant audio
        // must line up with.
        val observed =
            (localNanos / NANOS_PER_MICRO + DISPLAY_PRESENTATION_DELAY_US - serverTimestampUs).toDouble()

        val current = offsetUs
        if (current == null) {
            offsetUs = observed
            return
        }

        if (abs(observed - current) > OUTLIER_THRESHOLD_US) {
            consecutiveOutliers++
            if (consecutiveOutliers < OUTLIER_TOLERANCE_FRAMES) return
            // Sustained: this is a real change, not a drained backlog. Adopt it outright
            // rather than crawling there through the smoothing filter.
            offsetUs = observed
            consecutiveOutliers = 0
            return
        }

        consecutiveOutliers = 0
        offsetUs = current + OFFSET_SMOOTHING * (observed - current)
    }

    /**
     * Decides what to do with a chunk stamped [chunkServerTimestampUs] that `AudioTrack`
     * is predicted to start playing at [predictedPlayoutLocalNanos].
     *
     * [chunkFrameCount] bounds how much of this chunk may be corrected.
     */
    fun decide(
        chunkServerTimestampUs: Long,
        predictedPlayoutLocalNanos: Long,
        chunkFrameCount: Int,
    ): Decision {
        // No video yet: nothing to align against, so play rather than hold or discard.
        val offset = offsetUs ?: return Decision.Play

        val targetPlayoutUs = chunkServerTimestampUs + offset
        val predictedPlayoutUs = predictedPlayoutLocalNanos / NANOS_PER_MICRO
        // Positive: audio would be heard after its picture. Negative: before it.
        val skewUs = predictedPlayoutUs - targetPlayoutUs

        if (abs(skewUs) > UNRECOVERABLE_SKEW_US) return Decision.Discard

        if (skewUs > BEHIND_TOLERANCE_US) {
            // The whole chunk is already in the past: dropping it is not a splice, it just
            // moves on to the next one. Trimming its front instead would splice once per
            // chunk and take a dozen of them to clear a startup skew — audible every time.
            if (framesFor(skewUs) >= chunkFrameCount) return Decision.Discard

            val frames = correctionFrames(skewUs, chunkFrameCount, limit = chunkFrameCount.toLong())
            return if (frames > 0) Decision.SkipFrames(frames) else Decision.Play
        }

        if (-skewUs > AHEAD_TOLERANCE_US) {
            // Silence has no such ceiling: the skew is already bounded by
            // UNRECOVERABLE_SKEW_US, so at most that much silence is ever inserted.
            val frames = correctionFrames(-skewUs, chunkFrameCount, limit = Long.MAX_VALUE)
            return if (frames > 0) Decision.InsertSilence(frames) else Decision.Play
        }

        // Inside tolerance: the stream is in sync, so later corrections are rate-limited.
        isAligned = true
        return Decision.Play
    }

    /**
     * Sample frames needed to absorb [skewUs].
     *
     * Rate-limited to [MAX_CORRECTION_FRACTION] of the chunk only once the stream has been
     * aligned; the first alignment takes the whole skew at once (bounded by [limit]) so
     * the startup offset costs one adjustment instead of a run of audible splices.
     */
    /** Sample frames of this format that span [skewUs]. */
    private fun framesFor(skewUs: Double): Long =
        (skewUs * format.sampleRate / MICROS_PER_SECOND).toLong()

    private fun correctionFrames(skewUs: Double, chunkFrameCount: Int, limit: Long): Int {
        val needed = framesFor(skewUs)
        val allowed = if (isAligned) {
            minOf((chunkFrameCount * MAX_CORRECTION_FRACTION).toLong(), limit)
        } else {
            limit
        }
        return minOf(needed, allowed).coerceAtLeast(0L).toInt()
    }
}
