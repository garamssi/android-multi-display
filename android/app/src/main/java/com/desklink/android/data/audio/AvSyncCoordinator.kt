package com.desklink.android.data.audio

import kotlin.math.abs
import kotlin.math.ceil

// Video is the master clock: a mirror renders frames on arrival because latency is the
// point, so audio is steered onto the picture rather than the other way round. Both
// streams timestamp on one axis (host uptime microseconds on the Mac), so the
// server-to-local offset learned from rendered frames says when a chunk should be heard.
//
// The actuator is PLAYBACK RATE, not splicing. Cutting or padding samples is a hard
// discontinuity, audible as a tick each time; a measured session produced 65 of them in
// 13.6 s, heard as continuous crackling. A trim of a fraction of a percent is a resample:
// inaudible, and still orders of magnitude more authority than real clock drift needs.
// Splicing survives only for a one-off resync, where playback has not started yet or is
// already too far out to trim back in reasonable time.
//
// Pure arithmetic: no Android APIs and no clock of its own, so the policy is testable.
class AvSyncCoordinator(
    private val format: AudioProtocol.AudioFormat,
) {

    companion object {
        // Broadcast practice tolerates audio leading by roughly 45 ms and trailing by
        // roughly 125 ms before a viewer notices. The band must also be WIDER than the
        // measurement noise on this path (~18 ms): a deadband narrower than the noise is
        // a noise tracker, correcting against jitter that is not real skew.
        const val AHEAD_TOLERANCE_US = 45_000L
        const val BEHIND_TOLERANCE_US = 125_000L

        // Past this, trimming would take minutes (the source was paused, the link
        // stalled), so the stream is realigned in one step instead.
        const val RESYNC_THRESHOLD_US = 250_000L

        // Once correcting, keep going until the skew is back near zero rather than
        // stopping at the tolerance. Without this the loop parks exactly on the band edge
        // — technically in tolerance, but sitting permanently at the limit of what a
        // viewer accepts, with no headroom before the next disturbance crosses it.
        const val RECAPTURE_US = 15_000L

        // Playback rate authority. 0.5% is about 8.6 cents of pitch shift, which is not
        // audible on program material, and absorbs 5 ms of skew per second: 121x the
        // worst plausible clock drift between the Mac's tap and this device's DAC. Lower
        // values were measurably too slow (150 ms took over two minutes to clear).
        const val MAX_RATE_TRIM = 0.005f

        // Upper bound on a single silence insert, as a multiple of the resync threshold.
        // The insert is a blocking write on the audio thread, so it must not be derived
        // without limit from one measurement.
        const val MAX_RESYNC_SILENCE_MULTIPLE = 2

        // Integral gain, per chunk. Small enough that it takes seconds of sustained error
        // to build authority (so it cannot react to noise the filter already removed),
        // large enough to cancel a few hundred ppm of drift well inside a minute.
        private const val INTEGRAL_GAIN = 1.0 / (BEHIND_TOLERANCE_US * 200)

        // Leak on the integral, per chunk. Without it the accumulator would hold the last
        // correction forever after the disturbance ends, overshooting once the error is
        // gone. At 0.999 the term decays over roughly ten seconds of chunks, which still
        // leaves ample standing authority to cancel drift (the steady-state accumulation
        // is gain/(1 - leak) = 1000x the per-chunk contribution).
        private const val INTEGRAL_LEAK = 0.999

        // Weight of each new observation in the offset estimate: low enough that ordinary
        // frame jitter barely moves the reference, high enough to follow a real change.
        private const val OFFSET_SMOOTHING = 0.05

        // The DECISION signal is filtered too, not just the offset. Filtering only the
        // offset left every correction riding on unfiltered per-chunk noise, which is how
        // jitter crossed the deadband and produced corrections for skew that was not there.
        private const val SKEW_SMOOTHING = 0.05

        // Deviation past which one observation is noise rather than news. A stalled
        // decoder that drains a backlog reports frames far from the running estimate;
        // feeding those in moved a true 50 ms offset to 204 ms in measurement.
        private const val OUTLIER_THRESHOLD_US = 150_000.0

        // Consecutive outliers after which the deviation is accepted as a real change, so
        // a genuine shift in transport delay is not rejected forever.
        private const val OUTLIER_TOLERANCE_FRAMES = 10

        private const val NANOS_PER_MICRO = 1_000L
        private const val MICROS_PER_SECOND = 1_000_000L
    }

    sealed interface Decision {
        // Write the chunk unchanged at this playback rate. 1.0 means no correction.
        data class Play(val playbackSpeed: Float) : Decision

        // Prepend silence to push the audio later. Resync only.
        data class InsertSilence(val frames: Int) : Decision

        // Drop the chunk to pull the audio earlier. Resync only.
        data object Discard : Decision
    }

    // Smoothed local-minus-server offset in microseconds; null until a frame is seen.
    // Volatile because frames are reported from the render thread while chunks are
    // decided on the audio thread.
    @Volatile
    private var offsetUs: Double? = null

    @Volatile
    private var consecutiveOutliers = 0

    // Filtered skew driving the decision; null until the first chunk is judged.
    private var filteredSkewUs: Double? = null

    private val maxResyncSilenceFrames: Long =
        RESYNC_THRESHOLD_US * MAX_RESYNC_SILENCE_MULTIPLE * format.sampleRate / MICROS_PER_SECOND

    // False until the stream has been brought into the band once. The startup offset is
    // far too large to trim away, so it is taken in a single step while playback is still
    // starting, where one large adjustment is inaudible.
    private var isAligned = false

    // True while actively trimming. Correction runs until the skew is back near zero, not
    // merely inside tolerance; see RECAPTURE_US.
    private var isCorrecting = false

    // Chunks still to be dropped to clear a backlog; see resyncDecision.
    private var pendingDiscardChunks = 0

    // Accumulated rate authority holding a constant drift; see trimmedSpeed.
    private var driftIntegral = 0.0

    // Records that a video frame stamped [serverTimestampUs] was presented at [localNanos].
    fun noteVideoRendered(serverTimestampUs: Long, localNanos: Long) {
        val observed = (localNanos / NANOS_PER_MICRO - serverTimestampUs).toDouble()
        val current = offsetUs
        if (current == null) {
            offsetUs = observed
            return
        }

        if (abs(observed - current) > OUTLIER_THRESHOLD_US) {
            consecutiveOutliers++
            if (consecutiveOutliers < OUTLIER_TOLERANCE_FRAMES) return
            offsetUs = observed
            consecutiveOutliers = 0
            return
        }

        consecutiveOutliers = 0
        offsetUs = current + OFFSET_SMOOTHING * (observed - current)
    }

    // Decides what to do with a chunk stamped [chunkServerTimestampUs] that AudioTrack is
    // predicted to start playing at [predictedPlayoutLocalNanos].
    fun decide(
        chunkServerTimestampUs: Long,
        predictedPlayoutLocalNanos: Long,
        chunkFrameCount: Int,
    ): Decision {
        // No video yet: nothing to align against, so play untouched.
        val offset = offsetUs ?: return Decision.Play(1.0f)

        val predictedPlayoutUs = predictedPlayoutLocalNanos / NANOS_PER_MICRO
        // Positive: audio would be heard after its picture. Negative: before it.
        val rawSkewUs = (predictedPlayoutUs - (chunkServerTimestampUs + offset))
        val skewUs = smoothSkew(rawSkewUs)

        // A backlog drop already in progress runs to completion before anything else is
        // decided; interleaving other decisions would leave part of it behind.
        consumePendingDiscard()?.let { return it }

        // Too far out to trim back: realign in one step. Also covers the startup offset,
        // where nothing has been aligned yet.
        if (abs(skewUs) > RESYNC_THRESHOLD_US || !isAligned) {
            resyncDecision(skewUs, chunkFrameCount)?.let { return it }
        }

        updateCorrectionState(skewUs)
        if (isCorrecting) return Decision.Play(trimmedSpeed(skewUs, applyProportional = true))

        isAligned = true
        // Inside the band the proportional term stands down, but the integral does not:
        // cancelling a constant clock drift requires holding a rate even when the residual
        // error is small, and that is exactly the region where the drift lives.
        return Decision.Play(trimmedSpeed(skewUs, applyProportional = false))
    }

    private fun updateCorrectionState(skewUs: Double) {
        if (isCorrecting) {
            if (abs(skewUs) < RECAPTURE_US) isCorrecting = false
            return
        }
        if (skewUs > BEHIND_TOLERANCE_US || -skewUs > AHEAD_TOLERANCE_US) isCorrecting = true
    }

    // One-step realignment, or null when the skew is already inside the band.
    private fun resyncDecision(skewUs: Double, chunkFrameCount: Int): Decision? {
        if (skewUs > BEHIND_TOLERANCE_US) {
            // The audio is late, so the backlog has to be thrown away. Dropping ONE chunk
            // only removes its own duration: for a 600 ms backlog that is 56 chunks, and
            // resetting the filter after each one made the skew look cleared, so it
            // re-accumulated and dropped again — 31 separate audible seams in measurement.
            // Committing to the whole run instead makes it a single seam.
            val chunkDurationUs = format.durationUs(chunkFrameCount.toLong())
            if (chunkDurationUs > 0) {
                pendingDiscardChunks = ceil(skewUs / chunkDurationUs).toInt().coerceAtLeast(1)
            } else {
                pendingDiscardChunks = 1
            }
            return consumePendingDiscard()
        }
        if (-skewUs > AHEAD_TOLERANCE_US) {
            val frames = (-skewUs * format.sampleRate / MICROS_PER_SECOND).toLong()
            if (frames > 0) {
                // Bounded: the silence is written with a blocking call on the audio
                // thread, and an unbounded insert derived from one noisy sample could
                // stall it for as long as that sample claimed.
                val bounded = frames.coerceAtMost(maxResyncSilenceFrames)
                beginRealignment()
                return Decision.InsertSilence(bounded.toInt())
            }
        }
        return null
    }

    // Returns the next Discard of an in-flight backlog drop, or null when it is finished.
    private fun consumePendingDiscard(): Decision? {
        if (pendingDiscardChunks <= 0) return null
        pendingDiscardChunks--
        if (pendingDiscardChunks == 0) beginRealignment()
        return Decision.Discard
    }

    // Resets the loop after a realignment has absorbed the skew.
    private fun beginRealignment() {
        isAligned = false
        isCorrecting = false
        // The skew has been absorbed, so neither the filter nor the accumulated rate
        // authority may keep describing it.
        filteredSkewUs = 0.0
        driftIntegral = 0.0
    }

    // Proportional-plus-integral trim. Positive skew (audio late) speeds playback up.
    //
    // The integral term is what makes a constant clock drift settle. Proportional control
    // needs a standing error to produce any output at all, so on its own it releases
    // correction at the recapture point, drifts back out to the tolerance, corrects, and
    // repeats — a sawtooth measured at 15 ms to 125 ms, leaving the audio near the limit
    // of what a viewer accepts most of the time. The integral accumulates the residual
    // until the rate exactly cancels the drift, and the error settles near zero.
    private fun trimmedSpeed(skewUs: Double, applyProportional: Boolean): Float {
        val proportional =
            if (applyProportional) (skewUs / BEHIND_TOLERANCE_US).coerceIn(-1.0, 1.0) else 0.0

        // Leaky, and clamped to the same authority as the proportional term, so a long
        // uncorrectable excursion cannot wind up a correction that then overshoots.
        driftIntegral = (driftIntegral * INTEGRAL_LEAK + skewUs * INTEGRAL_GAIN)
            .coerceIn(-1.0, 1.0)

        val combined = (proportional + driftIntegral).coerceIn(-1.0, 1.0)
        return 1.0f + (combined * MAX_RATE_TRIM).toFloat()
    }

    private fun smoothSkew(rawSkewUs: Double): Double {
        val current = filteredSkewUs
        val next = if (current == null) rawSkewUs else current + SKEW_SMOOTHING * (rawSkewUs - current)
        filteredSkewUs = next
        return next
    }
}
