package com.desklink.android.data

import com.desklink.android.data.audio.AudioProtocol
import com.desklink.android.data.audio.AvSyncCoordinator
import com.desklink.android.data.audio.AvSyncCoordinator.Decision
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lip-sync control policy.
 *
 * Video is the master clock: a mirror renders frames on arrival because latency is the
 * point, so audio is steered onto the picture rather than the other way round.
 *
 * The actuator is PLAYBACK RATE, not splicing. Cutting or padding samples is a hard
 * discontinuity — audible as a tick every time — and a measured session produced 65 of
 * them in 13.6 seconds, heard as continuous crackling. A rate trim of a fraction of a
 * percent is a resample, inaudible, and absorbs orders of magnitude more skew per second
 * than any real clock drift. Splicing is kept only for a one-off resync, where playback
 * has either not started or is already broken.
 */
class AvSyncCoordinatorTest {

    private val format = AudioProtocol.AudioFormat(sampleRate = 48_000, channelCount = 2, bitsPerSample = 16)
    private val quantumFrames = 512

    private fun coordinator() = AvSyncCoordinator(format)

    private fun Long.usAsNanos(): Long = this * 1_000

    private fun framesForUs(us: Long): Int = (us * format.sampleRate / 1_000_000L).toInt()

    /** Establishes a steady +50ms reference from rendered frames. */
    private fun AvSyncCoordinator.withReference(offsetUs: Long = 50_000L): AvSyncCoordinator {
        repeat(30) { index ->
            val serverUs = 1_000_000L + index * 16_667L
            noteVideoRendered(serverUs, (serverUs + offsetUs).usAsNanos())
        }
        return this
    }

    /** Decision for a chunk whose predicted playout is `skewUs` away from its target. */
    private fun AvSyncCoordinator.decideWithSkew(
        skewUs: Long,
        offsetUs: Long = 50_000L,
        chunkFrames: Int = 512,
    ): Decision {
        val chunkServerUs = 2_000_000L
        return decide(chunkServerUs, (chunkServerUs + offsetUs + skewUs).usAsNanos(), chunkFrames)
    }

    /**
     * Decision after the skew has been present long enough for the filter to see it.
     * The decision signal is deliberately smoothed, so a single sample never acts.
     */
    private fun AvSyncCoordinator.decideSustained(skewUs: Long, chunkFrames: Int = 512): Decision {
        var decision = decideWithSkew(skewUs, chunkFrames = chunkFrames)
        repeat(200) { decision = decideWithSkew(skewUs, chunkFrames = chunkFrames) }
        return decision
    }

    /** Brings a fresh coordinator into the aligned, non-correcting state. */
    private fun AvSyncCoordinator.settled(): AvSyncCoordinator {
        repeat(50) { decideWithSkew(0) }
        return this
    }

    // MARK: - No reference yet

    /** Audio can arrive before the first frame renders; there is nothing to align to. */
    @Test
    fun `plays at normal rate before any video frame is rendered`() {
        val decision = coordinator().decide(1_000_000L, 5_000_000L.usAsNanos(), quantumFrames)
        assertEquals(Decision.Play(1.0f), decision)
    }

    /** A single noisy sample must not move the rate meaningfully either. */
    @Test
    fun `a single noisy sample does not move the rate`() {
        val sync = coordinator().withReference().settled()
        val speed = (sync.decideWithSkew(-100_000) as Decision.Play).playbackSpeed
        assertTrue(
            abs(speed - 1.0f) < AvSyncCoordinator.MAX_RATE_TRIM / 10,
            "one outlier moved the rate to $speed",
        )
    }

    // MARK: - Steady state uses rate, never splices

    /**
     * The core requirement: once aligned, correction must NEVER splice. Every skew inside
     * the resync threshold is answered with a rate trim, which is inaudible.
     */
    @Test
    fun `steady state never splices`() {
        val sync = coordinator().withReference().settled()

        for (skewUs in -240_000L..240_000L step 5_000L) {
            val decision = sync.decideWithSkew(skewUs)
            assertTrue(
                decision is Decision.Play,
                "skew ${skewUs / 1000}ms produced $decision instead of a rate trim",
            )
        }
    }

    /**
     * Inside the band no large correction is applied. The rate is not bit-identical to 1.0
     * because the integral term keeps holding whatever cancels a standing drift, but with
     * no drift present that residual is far below anything audible or actionable.
     */
    @Test
    fun `applies no meaningful correction inside the deadband`() {
        val sync = coordinator().withReference().settled()
        for (skewUs in longArrayOf(0, 20_000, -20_000)) {
            val speed = (sync.decideWithSkew(skewUs) as Decision.Play).playbackSpeed
            assertTrue(
                abs(speed - 1.0f) < AvSyncCoordinator.MAX_RATE_TRIM / 10,
                "in-band skew ${skewUs / 1000}ms produced a rate of $speed",
            )
        }
    }

    /** Audio running behind the picture must be played slightly FASTER to catch up. */
    @Test
    fun `speeds up when audio lags the picture`() {
        val sync = coordinator().withReference().settled()
        val speed = (sync.decideSustained(200_000) as Decision.Play).playbackSpeed
        assertTrue(speed > 1.0f, "expected a speed-up, got $speed")
        assertTrue(speed <= 1.0f + AvSyncCoordinator.MAX_RATE_TRIM)
    }

    /** Audio running ahead must be played slightly SLOWER. */
    @Test
    fun `slows down when audio leads the picture`() {
        val sync = coordinator().withReference().settled()
        val speed = (sync.decideSustained(-200_000) as Decision.Play).playbackSpeed
        assertTrue(speed < 1.0f, "expected a slow-down, got $speed")
        assertTrue(speed >= 1.0f - AvSyncCoordinator.MAX_RATE_TRIM)
    }

    /**
     * The trim must stay inaudible. 0.2% is about 3.5 cents of pitch shift; anything
     * approaching a percent is heard as wow on tonal material.
     */
    @Test
    fun `rate trim stays inaudible`() {
        assertTrue(
            AvSyncCoordinator.MAX_RATE_TRIM <= 0.005f,
            "a trim above 0.5% is audible as pitch wobble",
        )
    }

    /**
     * The trim must still dwarf real clock drift between the Mac's tap and the tablet's
     * DAC, or the loop could never keep up. Drift is tens to hundreds of ppm.
     */
    @Test
    fun `rate trim dwarfs clock drift`() {
        val worstCaseDriftPpm = 500
        assertTrue(
            AvSyncCoordinator.MAX_RATE_TRIM > worstCaseDriftPpm / 1_000_000f * 5,
            "trim leaves no margin over clock drift",
        )
    }

    /**
     * Closed loop with an integral term must not overshoot through zero and start
     * correcting the other way — that would be a slow oscillation across the picture.
     */
    @Test
    fun `converges without overshooting past the other tolerance`() {
        val sync = coordinator().withReference().settled()
        val chunkDurationUs = format.durationUs(quantumFrames.toLong())
        var skewUs = 200_000.0
        var speed = 1.0f
        var mostNegative = 0.0

        repeat(60_000) {
            val decision = sync.decideWithSkew(skewUs.toLong())
            speed = (decision as? Decision.Play)?.playbackSpeed ?: speed
            skewUs -= (speed - 1.0f) * chunkDurationUs
            mostNegative = minOf(mostNegative, skewUs)
        }

        assertTrue(
            abs(skewUs) < AvSyncCoordinator.AHEAD_TOLERANCE_US,
            "did not settle: ${skewUs / 1000}ms",
        )
        assertTrue(
            mostNegative > -AvSyncCoordinator.AHEAD_TOLERANCE_US,
            "overshot to ${mostNegative / 1000}ms, past the opposite tolerance",
        )
    }

    // MARK: - Deadband must be wider than measurement noise

    /**
     * A deadband narrower than the measurement noise is a noise tracker: it corrects
     * constantly against jitter that is not real skew. Measured noise on this path is
     * roughly 16-18 ms, and broadcast practice tolerates far more than that.
     */
    @Test
    fun `deadband is wider than measurement noise`() {
        val measuredNoiseUs = 18_000L
        assertTrue(AvSyncCoordinator.AHEAD_TOLERANCE_US > measuredNoiseUs * 2)
        assertTrue(AvSyncCoordinator.BEHIND_TOLERANCE_US > measuredNoiseUs * 2)
    }

    /** Audio leading the picture is noticed sooner than audio trailing it. */
    @Test
    fun `ahead tolerance is tighter than behind tolerance`() {
        assertTrue(AvSyncCoordinator.AHEAD_TOLERANCE_US < AvSyncCoordinator.BEHIND_TOLERANCE_US)
    }

    // MARK: - Resync (the only place splicing survives)

    /**
     * A startup offset is too large to trim away at a fraction of a percent, so the first
     * alignment is done in one step while playback is still starting.
     */
    @Test
    fun `aligns once up front rather than trimming a startup offset`() {
        val sync = coordinator().withReference()
        val aheadByUs = 113_000L
        val decision = sync.decideWithSkew(-aheadByUs)  // first judged chunk
        val inserted = (decision as Decision.InsertSilence).frames
        assertEquals(framesForUs(aheadByUs), inserted)
    }

    @Test
    fun `discards to align when startup audio is far behind`() {
        val sync = coordinator().withReference()
        // Past the behind-tolerance, so the chunk is already in the past.
        assertEquals(Decision.Discard, sync.decideWithSkew(200_000))
    }

    /**
     * A behind-resync must clear the WHOLE backlog, not one chunk of it.
     *
     * Dropping a single 10.7 ms chunk removes 10.7 ms of a skew that may be hundreds of
     * milliseconds, while the filter is reset as though the skew were gone. The skew then
     * re-accumulates and another chunk is dropped, over and over: measured at 31 splices
     * for a 600 ms backlog, which is the same crackling this redesign set out to remove.
     */
    @Test
    fun `behind resync clears the whole backlog in one run`() {
        val sync = coordinator().withReference()
        val chunkDurationUs = format.durationUs(quantumFrames.toLong())
        var skewUs = 600_000L
        var discardRuns = 0
        var wasDiscarding = false

        repeat(4_000) {
            val decision = sync.decideWithSkew(skewUs)
            val isDiscard = decision == Decision.Discard
            if (isDiscard && !wasDiscarding) discardRuns++
            wasDiscarding = isDiscard
            // Dropping a chunk advances playback by that chunk's duration.
            if (isDiscard) skewUs -= chunkDurationUs
        }

        assertTrue(skewUs < AvSyncCoordinator.BEHIND_TOLERANCE_US, "backlog not cleared: ${skewUs / 1000}ms")
        // Consecutive drops are one audible seam; separate runs are separate ticks.
        assertEquals(1, discardRuns, "backlog was cleared in $discardRuns separate bursts")
    }

    /** After the one-off alignment, corrections switch to rate trimming for good. */
    @Test
    fun `switches to rate trimming after the initial alignment`() {
        val sync = coordinator().withReference()
        sync.decideWithSkew(-113_000)
        sync.settled()
        assertTrue(sync.decideSustained(-113_000) is Decision.Play)
    }

    /**
     * A skew past the resync threshold cannot be trimmed away in reasonable time (the
     * source was paused, the link stalled), so it is realigned in one step again.
     */
    @Test
    fun `resyncs when skew grows past the trimming range`() {
        val sync = coordinator().withReference().settled()
        val skew = -AvSyncCoordinator.RESYNC_THRESHOLD_US - 200_000
        // A resync absorbs the skew and resets the filter, so look for it happening at
        // all rather than at the state of the last decision.
        val resynced = (0 until 300).any { sync.decideWithSkew(skew) is Decision.InsertSilence }
        assertTrue(resynced, "skew past the trimming range never triggered a resync")
    }

    @Test
    fun `resync threshold is well outside the deadband`() {
        assertTrue(
            AvSyncCoordinator.RESYNC_THRESHOLD_US >= AvSyncCoordinator.BEHIND_TOLERANCE_US * 2,
        )
        assertTrue(AvSyncCoordinator.RECAPTURE_US < AvSyncCoordinator.AHEAD_TOLERANCE_US)
    }

    // MARK: - Skew filtering

    /**
     * The decision signal must be filtered, not just the offset. Without this the
     * coordinator chases measurement noise: the deadband is crossed by jitter and a
     * correction is issued for skew that is not really there.
     */
    @Test
    fun `a single noisy sample does not leave the deadband`() {
        val sync = coordinator().withReference().settled()
        // One sample 100ms out, well past the ahead tolerance on its own.
        assertTrue(sync.decideWithSkew(-100_000) is Decision.Play)
    }

    /** A sustained skew must still be acted on, or real drift would never be corrected. */
    @Test
    fun `sustained skew is acted on`() {
        val sync = coordinator().withReference().settled()
        val speed = (sync.decideSustained(-100_000) as Decision.Play).playbackSpeed
        assertTrue(speed < 1.0f, "sustained skew never produced a trim")
    }

    /**
     * With a constant clock drift the loop must SETTLE, not sweep the deadband.
     *
     * Proportional control alone needs a standing error to produce any output, so it
     * releases correction at the recapture point, drifts back to the tolerance, corrects,
     * and repeats — measured as a sawtooth spanning 15 ms to 125 ms. The audio then spends
     * most of its time near the limit of what a viewer tolerates. An integral term holds
     * the rate that cancels the drift, so the skew stays near zero.
     */
    @Test
    fun `holds a steady rate against constant drift instead of cycling`() {
        val sync = coordinator().withReference()
        val chunkDurationUs = format.durationUs(quantumFrames.toLong())
        // 500 ppm: large enough that a proportional-only loop completes several sawtooth
        // cycles inside this run. At 100 ppm the sweep is real but takes ~45 minutes, far
        // beyond what a unit test can simulate, so it would pass vacuously.
        val driftPpm = 500.0

        var skewUs = 0.0
        var speed = 1.0f
        var maxAbsSkew = 0.0

        // Long enough for a proportional-only loop to complete several sawtooth cycles.
        repeat(60_000) { index ->
            val decision = sync.decide(
                2_000_000L + (index * chunkDurationUs),
                ((2_000_000L + (index * chunkDurationUs)) + 50_000L + skewUs.toLong()).usAsNanos(),
                quantumFrames,
            )
            speed = (decision as? Decision.Play)?.playbackSpeed ?: speed
            // Drift pushes the skew out; the rate trim pulls it back.
            skewUs += chunkDurationUs * driftPpm / 1_000_000.0
            skewUs -= (speed - 1.0f) * chunkDurationUs
            // Ignore the initial acquisition transient.
            if (index > 5_000) maxAbsSkew = maxOf(maxAbsSkew, abs(skewUs))
        }

        assertTrue(
            maxAbsSkew < AvSyncCoordinator.AHEAD_TOLERANCE_US,
            "steady-state skew swung to ${maxAbsSkew / 1000}ms under $driftPpm ppm drift",
        )
    }

    // MARK: - Closed loop

    /**
     * Applying the rate the coordinator asks for must drive the skew back into the band,
     * without splicing and without oscillating.
     */
    @Test
    fun `rate control converges without splicing`() {
        val sync = coordinator().withReference().settled()

        val chunkDurationUs = format.durationUs(quantumFrames.toLong())
        var skewUs = 150_000L
        var speed = 1.0f
        var splices = 0
        var iterations = 0

        while (iterations < 20_000) {
            val decision = sync.decideWithSkew(skewUs)
            when (decision) {
                is Decision.Play -> speed = decision.playbackSpeed
                else -> splices++
            }
            // Playing faster than real time drains the queue, pulling the audio earlier.
            skewUs -= ((speed - 1.0f) * chunkDurationUs).toLong()
            iterations++
            if (speed == 1.0f && abs(skewUs) <= AvSyncCoordinator.RECAPTURE_US) break
        }

        assertEquals(0, splices, "rate control should never splice")
        assertTrue(
            abs(skewUs) <= AvSyncCoordinator.RECAPTURE_US,
            "did not converge to near zero: ${skewUs / 1000}ms after $iterations chunks",
        )
    }
}
