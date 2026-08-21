package com.desklink.android.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Log

// Plays received PCM through AudioTrack, steered onto the video clock.
//
// The PCM already has the layout AudioTrack wants (interleaved signed 16-bit
// little-endian, see the protocol spec), so the bytes go straight to the hardware with no
// per-sample work here. Timing policy lives in AvSyncCoordinator and PlayoutPredictor;
// this class owns the Android object, the frame counters and the diagnostics.
//
// Threading: every method except noteVideoRendered runs on the single audio thread that
// owns this player (see AudioStreamRepositoryImpl). That confinement is what makes stop()
// safe against an in-flight play(): the blocking write and the release cannot overlap on
// one thread. noteVideoRendered is the exception, called from the render thread, and the
// state it touches is volatile inside AvSyncCoordinator.
class AudioTrackPlayer(
    private val format: AudioProtocol.AudioFormat,
    private val sync: AvSyncCoordinator = AvSyncCoordinator(format),
    private val predictor: PlayoutPredictor = PlayoutPredictor(format),
) {

    private companion object {
        const val TAG = "AudioTrackPlayer"

        // Track buffer as a multiple of the device's minimum: enough that a scheduling
        // hiccup does not underrun, not so much that it adds latency the sync path then
        // has to correct.
        const val BUFFER_SIZE_MULTIPLIER = 4

        // Audio buffered before play() is called. Starting an empty track makes it
        // consume immediately while the HAL period here is only ~8 ms, so the stream
        // begins starved and crackles. The cost is 30 ms of latency, which is not a loss:
        // the video path runs tens of milliseconds behind, so a later audio start lands
        // closer to the picture.
        const val PRIMING_DURATION_US = 30_000L

        // Rate changes are only pushed to the hardware when they matter. Below this the
        // trim is indistinguishable and a setPlaybackParams call per chunk would be pure
        // overhead on the audio thread.
        const val RATE_CHANGE_EPSILON = 0.0001f

        const val MICROS_PER_SECOND = 1_000_000L
    }

    private var track: AudioTrack? = null

    // Sample frames handed to the track, including inserted silence.
    private var framesWritten = 0L

    private var isPlaying = false

    private var appliedSpeed = 1.0f

    // Latched once the device refuses playback params; see applySpeed.
    private var isRateControlUnavailable = false

    // Reused so the per-chunk polling path allocates nothing.
    private val timestamp = AudioTimestamp()

    private val primingFrames: Long = PRIMING_DURATION_US * format.sampleRate / MICROS_PER_SECOND

    // Diagnostics. Split by direction because a single "corrected" total cannot tell a
    // silence insertion from a discard, and those mean opposite things about the skew.
    private var silenceInsertions = 0L
    private var discards = 0L
    private var malformedChunks = 0L
    private var rateChanges = 0L
    private var chunksWithoutTimestamp = 0L
    private var chunksPlayed = 0L

    fun noteVideoRendered(serverTimestampUs: Long, localNanos: Long) {
        sync.noteVideoRendered(serverTimestampUs, localNanos)
    }

    // Opens the track. Returns false when the device cannot play this format, so the
    // caller can report it instead of leaving a player that silently drops every chunk.
    fun start(): Boolean {
        if (track != null) return true

        val channelMask = when (format.channelCount) {
            1 -> AndroidAudioFormat.CHANNEL_OUT_MONO
            else -> AndroidAudioFormat.CHANNEL_OUT_STEREO
        }
        val minBufferBytes = AudioTrack.getMinBufferSize(
            format.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            Log.e(TAG, "unsupported audio format: ${format.sampleRate} Hz, ${format.channelCount} ch")
            return false
        }

        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Mirrored desktop audio is media: it must follow the media volume and
                    // ducking rules.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(minBufferBytes * BUFFER_SIZE_MULTIPLIER)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        framesWritten = 0L
        isPlaying = false
        appliedSpeed = 1.0f
        isRateControlUnavailable = false
        resetDiagnostics()
        // play() is deliberately deferred until primed.
        track = newTrack
        Log.i(
            TAG,
            "audio track opened: ${format.sampleRate} Hz, ${format.channelCount} ch, " +
                "buffer=${newTrack.bufferSizeInFrames} frames, perf=${newTrack.performanceMode}",
        )
        return true
    }

    // Plays one chunk, applying whatever correction the coordinator asks for.
    fun play(chunk: AudioProtocol.AudioChunk) {
        val track = this.track ?: return
        if (!chunk.isConsistentWith(format)) {
            // Its duration cannot be trusted, and feeding it to the sync path would
            // corrupt the playout prediction everything else rests on.
            malformedChunks++
            return
        }

        if (!isPlaying) {
            write(track, chunk.pcm, 0, chunk.pcm.size)
            startPlaybackIfPrimed(track)
            return
        }

        chunksPlayed++
        val predicted = predictedPlayoutNanos()
        if (predicted == null) {
            // No trustworthy playout time yet: play unchanged rather than correct against
            // a guess. See predictedPlayoutNanos.
            chunksWithoutTimestamp++
            write(track, chunk.pcm, 0, chunk.pcm.size)
            return
        }

        when (val decision = sync.decide(chunk.timestampUs, predicted, chunk.frameCount)) {
            is AvSyncCoordinator.Decision.Play -> {
                applySpeed(track, decision.playbackSpeed)
                write(track, chunk.pcm, 0, chunk.pcm.size)
            }

            is AvSyncCoordinator.Decision.InsertSilence -> {
                silenceInsertions++
                val silence = ByteArray(decision.frames * format.bytesPerFrame)
                write(track, silence, 0, silence.size)
                write(track, chunk.pcm, 0, chunk.pcm.size)
            }

            AvSyncCoordinator.Decision.Discard -> discards++
        }
    }

    fun stop() {
        val current = track ?: return
        track = null
        runCatching {
            // A track still priming was never started, so pause/stop would throw.
            if (isPlaying) {
                current.pause()
                current.flush()
                current.stop()
            }
        }.onFailure { Log.e(TAG, "error stopping audio track: $it") }
        logSessionSummary(current)
        current.release()
        isPlaying = false
    }

    // Pushes a new playback rate only when it actually differs.
    //
    // Pitch is set to the SAME value as speed on purpose. With pitch left at 1.0 the
    // framework preserves pitch by time-stretching: sonic's PICOLA algorithm deletes or
    // repeats whole pitch periods (2.5-15 ms of audio) and crossfades the seam, which at
    // a 0.5% trim is an edit every 0.5-3 seconds — the very artefact rate control was
    // adopted to avoid. Matching pitch to speed cancels the stretch and leaves a pure
    // resample: the clip plays 0.5% faster and 8.6 cents higher, which is inaudible.
    private fun applySpeed(track: AudioTrack, speed: Float) {
        if (kotlin.math.abs(speed - appliedSpeed) < RATE_CHANGE_EPSILON) return
        val result = runCatching {
            track.playbackParams = PlaybackParams().setSpeed(speed).setPitch(speed)
        }
        if (result.isFailure) {
            // A FAST/low-latency output track rejects playback params outright, which
            // would silently disable the steady-state actuator for the whole session and
            // leave only the resync splices this design exists to avoid. Latch it so the
            // failure is visible in the session summary instead of scrolling past once.
            if (!isRateControlUnavailable) {
                isRateControlUnavailable = true
                Log.e(TAG, "playback rate control unavailable, sync limited to resync: ${result.exceptionOrNull()}")
            }
            return
        }
        appliedSpeed = speed
        rateChanges++
    }

    private fun startPlaybackIfPrimed(track: AudioTrack) {
        if (framesWritten < primingFrames) return
        track.play()
        isPlaying = true
        Log.i(TAG, "audio playback started after priming $framesWritten frames")
    }

    // Writes the whole range, retrying on a short write. WRITE_BLOCKING can still return
    // early; dropping the remainder loses the tail of a chunk and silently breaks the
    // frame accounting the playout prediction rests on.
    private fun write(track: AudioTrack, data: ByteArray, offset: Int, size: Int) {
        var position = offset
        var remaining = size
        while (remaining > 0) {
            val written = track.write(data, position, remaining, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                // Negative is an error; zero means no progress, and looping would spin.
                if (written < 0) Log.e(TAG, "AudioTrack.write failed: $written")
                return
            }
            framesWritten += written / format.bytesPerFrame
            position += written
            remaining -= written
        }
    }

    // When the next frame written will be heard, or null if that is not yet knowable.
    //
    // Only getTimestamp is used: it is the one source on the same axis as the video
    // reference, reporting true device presentation time including output latency. A
    // buffer-fill estimate was tried and was worse than nothing — playbackHeadPosition
    // counts frames the mixer consumed and excludes downstream latency (20-80 ms), so it
    // reads as "audio is early", and every transition to a real timestamp then unwound
    // that as lateness.
    //
    // The result is NOT floored at "now". Clamping is one-sided, so whenever it engaged it
    // biased the error in a single direction and rectified noise into a standing skew.
    private fun predictedPlayoutNanos(): Long? {
        val track = this.track ?: return null
        if (!track.getTimestamp(timestamp)) return null
        return predictor.predictFromTimestamp(
            framesWritten = framesWritten,
            reportedFramePosition = timestamp.framePosition,
            reportedNanos = timestamp.nanoTime,
            playbackSpeed = appliedSpeed,
        )
    }

    private fun resetDiagnostics() {
        silenceInsertions = 0
        discards = 0
        malformedChunks = 0
        rateChanges = 0
        chunksWithoutTimestamp = 0
        chunksPlayed = 0
    }

    private fun logSessionSummary(track: AudioTrack) {
        // Splices are what the listener hears as ticks, so they are reported separately
        // from rate changes, which are inaudible by construction.
        Log.i(
            TAG,
            "audio session ended: chunks=$chunksPlayed splices=$silenceInsertions " +
                "discards=$discards rateChanges=$rateChanges " +
                "noTimestamp=$chunksWithoutTimestamp malformed=$malformedChunks " +
                "underruns=${track.underrunCount} rateControl=${!isRateControlUnavailable}",
        )
    }
}
