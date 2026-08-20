package com.desklink.android.data

import com.desklink.android.data.audio.AudioProtocol
import com.desklink.android.data.audio.AvSyncCoordinator
import com.desklink.android.data.audio.AvSyncCoordinator.Decision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lip-sync decisions.
 *
 * The video path renders on arrival (vsync-driven, no PTS scheduling) because latency is
 * the point of a mirror. So VIDEO is the master clock and audio is steered onto it: the
 * coordinator learns the server->local time offset from frames as they are rendered, and
 * then decides whether each audio chunk should play as-is, be nudged earlier by skipping
 * frames, or be delayed by inserting silence.
 *
 * Thresholds are asymmetric on purpose: audio arriving AHEAD of the picture is noticed at
 * a far smaller offset than audio arriving behind it.
 */
class AvSyncCoordinatorTest {

    private val format = AudioProtocol.AudioFormat(sampleRate = 48_000, channelCount = 2, bitsPerSample = 16)

    private fun coordinator() = AvSyncCoordinator(format)

    /** Microseconds -> nanoseconds, for the local-clock arguments. */
    private fun Long.usAsNanos(): Long = this * 1_000

    private fun framesForUs(us: Long): Int = (us * format.sampleRate / 1_000_000L).toInt()

    /**
     * Decision for a chunk that is perfectly in sync against a steady-state +50ms offset,
     * including the fixed display-presentation term.
     */
    private fun inSyncDecision(sync: AvSyncCoordinator): Decision {
        val target = 2_000_000L + 50_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        return sync.decide(2_000_000L, target.usAsNanos(), 512)
    }

    // MARK: - Before a video reference exists

    /**
     * Audio can arrive before the first frame is rendered. With no video reference there
     * is nothing to align to, so it must play rather than be held or discarded.
     */
    @Test
    fun `plays unmodified before any video frame is rendered`() {
        val sync = coordinator()
        // Wildly out of sync, but with no reference there is nothing to align against.
        assertEquals(Decision.Play, sync.decide(1_000_000L, 5_000_000L.usAsNanos(), 512))
    }

    /** Once a frame is seen, the same wildly-skewed chunk is no longer played as-is. */
    @Test
    fun `acquires a video reference from a rendered frame`() {
        val sync = coordinator()
        sync.noteVideoRendered(serverTimestampUs = 1_000_000L, localNanos = 1_050_000L.usAsNanos())
        assertTrue(sync.decide(1_000_000L, 5_000_000L.usAsNanos(), 512) != Decision.Play)
    }

    // MARK: - In-sync case

    /**
     * Audio predicted to land at the same local time as its matching picture needs no
     * correction. Correcting here would inject clicks for nothing.
     */
    @Test
    fun `plays unmodified when audio lands with the picture`() {
        val sync = coordinator()
        // A frame stamped 1_000_000 was rendered locally at 1_050_000 -> offset +50ms.
        sync.noteVideoRendered(1_000_000L, 1_050_000L.usAsNanos())
        assertEquals(Decision.Play, inSyncDecision(sync))
    }

    @Test
    fun `plays unmodified inside the deadband`() {
        val sync = coordinator()
        sync.noteVideoRendered(1_000_000L, 1_050_000L.usAsNanos())
        val target = 2_050_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        // Just inside both asymmetric thresholds.
        assertEquals(Decision.Play, sync.decide(2_000_000L, (target + 10_000).usAsNanos(), 512))
        assertEquals(Decision.Play, sync.decide(2_000_000L, (target - 10_000).usAsNanos(), 512))
    }

    // MARK: - Audio behind the picture -> skip frames

    /**
     * Audio predicted to play AFTER its picture is nudged earlier by dropping frames —
     * but only when the chunk is longer than the skew. Trimming the front of a chunk that
     * is wholly in the past would leave nothing useful.
     *
     * A chunk of 4800 frames (100 ms) is used here because a real Core Audio quantum is
     * ~512 frames (10.7 ms), which is shorter than [BEHIND_TOLERANCE_US]; at that size a
     * late chunk is always discarded outright (see the test below).
     */
    @Test
    fun `skips frames when audio would play behind the picture`() {
        val sync = coordinator()
        sync.noteVideoRendered(1_000_000L, 1_050_000L.usAsNanos())
        val lateByUs = 70_000L
        val target = 2_050_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        val decision = sync.decide(2_000_000L, (target + lateByUs).usAsNanos(), 4_800)
        assertTrue(decision is Decision.SkipFrames, "expected SkipFrames, got $decision")
    }

    /**
     * At a real render quantum a late chunk is entirely in the past, so it is discarded
     * rather than spliced. This is what keeps a startup skew from costing one audible
     * splice per chunk.
     */
    @Test
    fun `discards a late chunk shorter than the skew`() {
        val sync = coordinator()
        sync.noteVideoRendered(1_000_000L, 1_050_000L.usAsNanos())
        val target = 2_050_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        // 70ms of skew against a 512-frame (10.7ms) quantum.
        assertEquals(Decision.Discard, sync.decide(2_000_000L, (target + 70_000L).usAsNanos(), 512))
    }

    /**
     * The FIRST alignment is not rate-limited.
     *
     * `AudioTrack.getTimestamp` reports nothing until enough audio has played, so the
     * first chunks are written with no correction at all and a startup skew builds up.
     * Paying that off at the steady-state rate meant dozens of splices in the first
     * seconds — measured at 22 corrections in one session, each an audible tick, which is
     * the "crackling on connect" this is here to prevent. Aligning once, up front, costs a
     * single adjustment while the stream is still starting.
     */
    @Test
    fun `first alignment is not rate limited`() {
        val sync = coordinator()
        sync.noteVideoRendered(1_000_000L, 1_050_000L.usAsNanos())
        val chunkFrames = 512
        val target = 2_050_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        val aheadByUs = 200_000L

        val decision = sync.decide(2_000_000L, (target - aheadByUs).usAsNanos(), chunkFrames)
        val inserted = (decision as Decision.InsertSilence).frames
        // The whole 200ms in one go, not 5% of a 512-frame chunk.
        assertEquals(framesForUs(aheadByUs), inserted)
        assertTrue(inserted > (chunkFrames * AvSyncCoordinator.MAX_CORRECTION_FRACTION).toInt())
    }

    /** Correction is gradual once aligned: one chunk must not jump the whole gap. */
    @Test
    fun `limits how much one chunk may be corrected after alignment`() {
        val sync = coordinator()
        sync.noteVideoRendered(1_000_000L, 1_050_000L.usAsNanos())
        val chunkFrames = 4_800
        val target = 2_050_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        // Settle first: a chunk already in sync marks the stream aligned.
        assertEquals(Decision.Play, sync.decide(2_000_000L, target.usAsNanos(), chunkFrames))

        val decision = sync.decide(2_000_000L, (target + 70_000L).usAsNanos(), chunkFrames)
        val skipped = (decision as Decision.SkipFrames).frames
        assertTrue(skipped <= chunkFrames, "correction $skipped exceeded the chunk itself")
        assertEquals((chunkFrames * AvSyncCoordinator.MAX_CORRECTION_FRACTION).toInt(), skipped)
    }

    /**
     * The steady-state rate must stay far above real clock drift while being small enough
     * that a splice is not audible. Drift is about 41 ppm (see the protocol spec).
     */
    @Test
    fun `steady state correction rate dwarfs clock drift`() {
        val driftFractionPerSecond = 41e-6
        assertTrue(
            AvSyncCoordinator.MAX_CORRECTION_FRACTION > driftFractionPerSecond * 100,
            "correction rate leaves no margin over clock drift",
        )
        // A 512-frame quantum is ~10.7ms; the capped splice must be a fraction of a ms.
        val cappedUs = format.durationUs(
            (512 * AvSyncCoordinator.MAX_CORRECTION_FRACTION).toLong()
        )
        assertTrue(cappedUs in 1..2_000, "steady-state splice is $cappedUs us, long enough to hear")
    }

    // MARK: - Audio ahead of the picture -> insert silence

    /**
     * Audio predicted to play BEFORE its picture must be delayed with silence. This is
     * the direction humans notice first, so its threshold is tighter.
     */
    @Test
    fun `inserts silence when audio would play ahead of the picture`() {
        val sync = coordinator()
        sync.noteVideoRendered(1_000_000L, 1_050_000L.usAsNanos())
        val target = 2_050_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        val decision = sync.decide(2_000_000L, (target - 100_000L).usAsNanos(), 4_800)
        assertTrue(decision is Decision.InsertSilence, "expected InsertSilence, got $decision")
    }

    /**
     * The ahead-threshold must be strictly tighter than the behind-threshold, because
     * audio leading the picture is perceptually worse at the same magnitude.
     */
    @Test
    fun `ahead threshold is tighter than behind threshold`() {
        assertTrue(
            AvSyncCoordinator.AHEAD_TOLERANCE_US < AvSyncCoordinator.BEHIND_TOLERANCE_US,
            "audio leading the picture must be corrected sooner than audio trailing it",
        )
    }

    /** A skew of exactly the ahead-tolerance is still acceptable; one past it is not. */
    @Test
    fun `corrects just past the ahead tolerance`() {
        val sync = coordinator()
        sync.noteVideoRendered(1_000_000L, 1_050_000L.usAsNanos())
        val target = 2_050_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        val tolerance = AvSyncCoordinator.AHEAD_TOLERANCE_US
        assertEquals(Decision.Play, sync.decide(2_000_000L, (target - tolerance).usAsNanos(), 4_800))
        assertTrue(
            sync.decide(2_000_000L, (target - tolerance - 20_000).usAsNanos(), 4_800) is Decision.InsertSilence
        )
    }

    // MARK: - Unrecoverable skew

    /**
     * Past a certain point, nudging a chunk at a time cannot catch up (the source was
     * paused, the link stalled). Discarding the stale chunk resynchronizes immediately,
     * which is better than a long stretch of audibly wrong audio.
     */
    @Test
    fun `discards a chunk that is unrecoverably stale`() {
        val sync = coordinator()
        sync.noteVideoRendered(1_000_000L, 1_050_000L.usAsNanos())
        val target = 2_050_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        val decision = sync.decide(
            2_000_000L,
            (target + AvSyncCoordinator.UNRECOVERABLE_SKEW_US + 100_000L).usAsNanos(),
            480,
        )
        assertEquals(Decision.Discard, decision)
    }

    @Test
    fun `unrecoverable threshold is well past the correction thresholds`() {
        assertTrue(AvSyncCoordinator.UNRECOVERABLE_SKEW_US > AvSyncCoordinator.BEHIND_TOLERANCE_US * 2)
    }

    // MARK: - Offset smoothing

    /**
     * A single late frame must not swing the reference far enough to trigger a
     * correction. Frame delivery jitters, and chasing that noise injects artifacts.
     */
    @Test
    fun `smooths the offset against a single outlier frame`() {
        val sync = coordinator()
        repeat(20) { index ->
            val serverUs = 1_000_000L + index * 16_000L
            sync.noteVideoRendered(serverUs, (serverUs + 50_000L).usAsNanos())
        }
        sync.noteVideoRendered(1_320_000L, (1_320_000L + 350_000L).usAsNanos())
        assertEquals(Decision.Play, inSyncDecision(sync))
    }

    /**
     * A severe outlier must not move the reference either. A stalled decoder that drains
     * a backlog in one vsync reports many frames with the SAME local time, so the
     * observed offset briefly looks enormous; letting that through would make the
     * coordinator inject silence against perfectly synced audio for hundreds of
     * milliseconds afterwards.
     */
    @Test
    fun `rejects a severe outlier frame`() {
        val sync = coordinator()
        repeat(20) { index ->
            val serverUs = 1_000_000L + index * 16_000L
            sync.noteVideoRendered(serverUs, (serverUs + 50_000L).usAsNanos())
        }
        // One frame reported 600ms late — far past ordinary jitter.
        sync.noteVideoRendered(1_320_000L, (1_320_000L + 650_000L).usAsNanos())
        assertEquals(Decision.Play, inSyncDecision(sync), "a single severe outlier moved the reference")
    }

    /**
     * A whole backlog drained in one vsync reports many frames at one local time. None of
     * them may shift the reference — this is the measured failure that turned a 50ms
     * offset into 204ms and produced a second of wrong corrections.
     */
    @Test
    fun `rejects a burst of frames reported at one local time`() {
        val sync = coordinator()
        repeat(20) { index ->
            val serverUs = 1_000_000L + index * 16_000L
            sync.noteVideoRendered(serverUs, (serverUs + 50_000L).usAsNanos())
        }
        // 30 frames drained at once, all stamped with the same local render time.
        val burstLocalNanos = (1_820_000L + 50_000L).usAsNanos()
        repeat(30) { index ->
            sync.noteVideoRendered(1_320_000L + index * 16_000L, burstLocalNanos)
        }
        assertEquals(Decision.Play, inSyncDecision(sync), "a drained backlog corrupted the reference")
    }

    /**
     * A SUSTAINED change is not an outlier and must be adopted, or a real shift in
     * transport delay would be rejected forever.
     */
    @Test
    fun `adopts a sustained large change in offset`() {
        val sync = coordinator()
        repeat(20) { index ->
            val serverUs = 1_000_000L + index * 16_000L
            sync.noteVideoRendered(serverUs, (serverUs + 50_000L).usAsNanos())
        }
        // Delay jumps to 600ms and stays there.
        repeat(200) { index ->
            val serverUs = 2_000_000L + index * 16_000L
            sync.noteVideoRendered(serverUs, (serverUs + 600_000L).usAsNanos())
        }
        // Aligned against the new offset (plus the fixed display-latency term).
        val target = 5_000_000L + 600_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        assertEquals(Decision.Play, sync.decide(5_000_000L, target.usAsNanos(), 512))
    }

    /**
     * Closed loop: applying the correction the coordinator asks for must REDUCE the skew
     * on the next chunk. Single-shot tests cannot catch a policy that diverges.
     */
    @Test
    fun `applying corrections converges toward sync`() {
        val sync = coordinator()
        val offsetUs = 50_000L
        repeat(20) { index ->
            val serverUs = 1_000_000L + index * 16_000L
            sync.noteVideoRendered(serverUs, (serverUs + offsetUs).usAsNanos())
        }

        // A real Core Audio render quantum, which the rest of the suite never exercises.
        val chunkFrames = 512
        val chunkDurationUs = format.durationUs(chunkFrames.toLong())
        var skewUs = 200_000L
        var chunkServerUs = 2_000_000L
        var steps = 0
        var audibleSplices = 0

        while (steps < 200) {
            val target = chunkServerUs + offsetUs + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
            val decision = sync.decide(chunkServerUs, (target + skewUs).usAsNanos(), chunkFrames)
            if (decision == Decision.Play) break
            // A discarded chunk is not spliced: playback simply advances to the next one,
            // pulling the audio forward by that chunk's duration.
            val applied = when (decision) {
                is Decision.SkipFrames -> format.durationUs(decision.frames.toLong())
                is Decision.InsertSilence -> -format.durationUs(decision.frames.toLong())
                Decision.Discard -> chunkDurationUs
                Decision.Play -> 0L
            }
            if (decision is Decision.SkipFrames || decision is Decision.InsertSilence) {
                audibleSplices++
            }
            val previous = skewUs
            skewUs -= applied
            assertTrue(
                kotlin.math.abs(skewUs) < kotlin.math.abs(previous),
                "correction did not reduce skew: $previous -> $skewUs",
            )
            chunkServerUs += chunkDurationUs
            steps++
        }
        assertTrue(steps in 1..199, "loop did not converge (steps=$steps)")
        assertTrue(kotlin.math.abs(skewUs) <= AvSyncCoordinator.BEHIND_TOLERANCE_US)
        // The point of the one-shot alignment: clearing a startup skew must not cost the
        // run of audible splices that produced the crackle on connect.
        assertTrue(audibleSplices <= 2, "startup skew took $audibleSplices audible splices")
    }

    /**
     * The per-chunk cap must be a whole number of frames at the real quantum size, and
     * must not round down to zero (which would stall correction entirely).
     */
    /**
     * At the real quantum size the steady-state cap must still round to a usable, non-zero
     * number of frames — a cap that floors to zero would stall drift correction entirely.
     * Tested on the silence side, which has no chunk-length ceiling.
     */
    @Test
    fun `cap is usable at a real render quantum size`() {
        val sync = coordinator()
        repeat(20) { index ->
            val serverUs = 1_000_000L + index * 16_000L
            sync.noteVideoRendered(serverUs, (serverUs + 50_000L).usAsNanos())
        }
        val target = 2_000_000L + 50_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        assertEquals(Decision.Play, sync.decide(2_000_000L, target.usAsNanos(), 512))

        val decision = sync.decide(2_000_000L, (target - 70_000L).usAsNanos(), 512)
        val inserted = (decision as Decision.InsertSilence).frames
        assertEquals((512 * AvSyncCoordinator.MAX_CORRECTION_FRACTION).toInt(), inserted)
        assertTrue(inserted > 0)
    }

    /**
     * Audio arriving unrecoverably EARLY must also be discarded. Padding it with silence
     * instead means minutes of injected silence at the correction rate.
     */
    @Test
    fun `discards a chunk that is unrecoverably early`() {
        val sync = coordinator()
        sync.noteVideoRendered(1_000_000L, 1_050_000L.usAsNanos())
        val target = 2_000_000L + 50_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        val decision = sync.decide(
            2_000_000L,
            (target - AvSyncCoordinator.UNRECOVERABLE_SKEW_US - 100_000L).usAsNanos(),
            512,
        )
        assertEquals(Decision.Discard, decision)
    }

    /**
     * Frames are queued to SurfaceFlinger by releaseOutputBuffer, not shown by it: the
     * picture appears at least one vsync later. Without accounting for that the reference
     * understates video latency and audio plays permanently early — in the direction the
     * tolerance is tightest, so it would never be corrected.
     */
    @Test
    fun `accounts for display presentation delay`() {
        assertTrue(
            AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US > 0,
            "presentation delay must be accounted for, or audio leads the picture",
        )
        val sync = coordinator()
        sync.noteVideoRendered(1_000_000L, 1_050_000L.usAsNanos())
        // The picture for a frame stamped 2_000_000 is really seen at +50ms +delay.
        val seenAtUs = 2_050_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        assertEquals(Decision.Play, sync.decide(2_000_000L, seenAtUs.usAsNanos(), 512))
    }

    /**
     * A sustained shift in the offset MUST be followed, or a genuine change in transport
     * delay would never be corrected.
     */
    @Test
    fun `tracks a sustained change in offset`() {
        val sync = coordinator()
        repeat(10) { index ->
            val serverUs = 1_000_000L + index * 16_000L
            sync.noteVideoRendered(serverUs, (serverUs + 50_000L).usAsNanos())
        }
        // Transport delay grows to 150ms and stays there.
        repeat(100) { index ->
            val serverUs = 1_200_000L + index * 16_000L
            sync.noteVideoRendered(serverUs, (serverUs + 150_000L).usAsNanos())
        }
        // Chunks must now be aligned against the new +150ms offset.
        val target = 3_150_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        assertEquals(Decision.Play, sync.decide(3_000_000L, target.usAsNanos(), 512))
    }

    // MARK: - Frame arithmetic

    /** Corrections are expressed in whole sample frames of THIS format. */
    @Test
    fun `correction is expressed in sample frames of the stream format`() {
        val sync = coordinator()
        sync.noteVideoRendered(1_000_000L, 1_050_000L.usAsNanos())
        val chunkFrames = 48_000 // one second, so the cap is not the binding constraint
        val aheadByUs = 100_000L
        val target = 2_050_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        val decision = sync.decide(2_000_000L, (target - aheadByUs).usAsNanos(), chunkFrames)
        val inserted = (decision as Decision.InsertSilence).frames
        // 100ms at 48 kHz is 4800 frames; the per-chunk cap must not bite at this size.
        assertEquals(framesForUs(aheadByUs), inserted)
    }

    @Test
    fun `never returns a non positive correction`() {
        val sync = coordinator()
        sync.noteVideoRendered(1_000_000L, 1_050_000L.usAsNanos())
        val target = 2_050_000L + AvSyncCoordinator.DISPLAY_PRESENTATION_DELAY_US
        for (offsetUs in longArrayOf(-300_000, -100_000, -31_000, 0, 31_000, 100_000, 300_000)) {
            val decision = sync.decide(2_000_000L, (target + offsetUs).usAsNanos(), 4_800)
            when (decision) {
                is Decision.SkipFrames -> assertTrue(decision.frames > 0, "skip must be positive")
                is Decision.InsertSilence -> assertTrue(decision.frames > 0, "silence must be positive")
                else -> Unit
            }
        }
    }
}
