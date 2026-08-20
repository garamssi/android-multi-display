package com.desklink.android.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log

/**
 * Plays received PCM through `AudioTrack`, steering it onto the video clock.
 *
 * The PCM arrives in exactly the layout `AudioTrack` wants (interleaved signed 16-bit
 * little-endian — see the protocol spec), so the bytes go straight to the hardware with
 * no per-sample conversion on this device.
 *
 * Every timing decision is delegated: [AvSyncCoordinator] decides what to do with a
 * chunk, [PlayoutPredictor] works out when a write will really be heard. This class only
 * owns the Android object and the frame counters.
 */
/**
 * Threading: every method except [noteVideoRendered] must be called from the single audio
 * thread that owns this player (see `AudioStreamRepositoryImpl`). That confinement is what
 * makes [stop] safe against an in-flight [play] — the blocking write and the release
 * cannot overlap if they are the same thread. [noteVideoRendered] is the one exception,
 * called from the render thread, and the state it touches lives behind a volatile field in
 * [AvSyncCoordinator].
 */
class AudioTrackPlayer(
    private val format: AudioProtocol.AudioFormat,
    private val sync: AvSyncCoordinator = AvSyncCoordinator(format),
    private val predictor: PlayoutPredictor = PlayoutPredictor(format),
) {

    private companion object {
        const val TAG = "AudioTrackPlayer"

        /**
         * Track buffer as a multiple of the device's minimum. Big enough that a
         * scheduling hiccup does not underrun, small enough that it does not add latency
         * the sync path then has to correct: buffered audio is unavoidable delay.
         */
        const val BUFFER_SIZE_MULTIPLIER = 4

        /**
         * Audio buffered before playback is started, in microseconds.
         *
         * `AudioTrack.play()` on an empty track starts consuming immediately, and the
         * device's HAL period here is only about 8 ms — so the first chunks arrive into a
         * track that is already starved and the start of the stream crackles. Priming
         * first means playback begins with a cushion.
         *
         * 30 ms costs 30 ms of audio latency, which is not a loss here: the video path
         * runs tens of milliseconds behind (capture, encode, transport, decode), so a
         * slightly later audio start lands closer to the picture, not further from it.
         */
        const val PRIMING_DURATION_US = 30_000L

        private const val MICROS_PER_SECOND = 1_000_000L
    }

    private var track: AudioTrack? = null

    /** Sample frames handed to the track, including inserted silence. */
    private var framesWritten = 0L

    /** False until enough audio is buffered to start playback without starving. */
    private var isPlaying = false

    /** Sample frames that must be buffered before playback starts. */
    private val primingFrames: Long =
        PRIMING_DURATION_US * format.sampleRate / MICROS_PER_SECOND

    /** Reused across calls so the polling path allocates nothing per chunk. */
    private val timestamp = AudioTimestamp()

    private var discardedChunks = 0L
    private var correctedChunks = 0L

    /** Records that a video frame was rendered, establishing the sync reference. */
    fun noteVideoRendered(serverTimestampUs: Long, localNanos: Long) {
        sync.noteVideoRendered(serverTimestampUs, localNanos)
    }

    /**
     * Opens the track. Returns false when the device cannot play this format, so the
     * caller can report it instead of leaving a player that silently drops every chunk.
     */
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
                    // MEDIA rather than a call/notification usage: this is mirrored
                    // desktop audio, so it must follow the media volume and ducking rules.
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
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        framesWritten = 0L
        discardedChunks = 0L
        correctedChunks = 0L
        isPlaying = false
        // Deliberately NOT calling play() yet — see PRIMING_DURATION_US.
        track = newTrack
        Log.i(TAG, "audio track opened: ${format.sampleRate} Hz, ${format.channelCount} ch")
        return true
    }

    /**
     * Plays one chunk, applying whatever correction the coordinator asks for.
     *
     * A chunk whose declared frame count disagrees with its PCM is refused: its duration
     * cannot be trusted, and feeding it to the sync path would corrupt the playout
     * prediction that everything else depends on.
     */
    fun play(chunk: AudioProtocol.AudioChunk) {
        val track = this.track ?: return
        if (!chunk.isConsistentWith(format)) {
            discardedChunks++
            Log.e(TAG, "dropped AUDIO_FRAME: frameCount=${chunk.frameCount} pcm=${chunk.pcm.size}")
            return
        }

        // While priming, write everything unchanged: there is no playback to be in sync
        // with yet, and correcting against a track that has not started is meaningless.
        if (!isPlaying) {
            write(track, chunk.pcm, 0, chunk.pcm.size)
            startPlaybackIfPrimed(track)
            return
        }

        // No trustworthy playout time yet: play unchanged rather than correct against a
        // guess. See predictedPlayoutNanos.
        val predicted = predictedPlayoutNanos() ?: run {
            write(track, chunk.pcm, 0, chunk.pcm.size)
            return
        }

        when (val decision = sync.decide(chunk.timestampUs, predicted, chunk.frameCount)) {
            AvSyncCoordinator.Decision.Play -> write(track, chunk.pcm, 0, chunk.pcm.size)

            is AvSyncCoordinator.Decision.SkipFrames -> {
                correctedChunks++
                val offset = decision.frames * format.bytesPerFrame
                if (offset < chunk.pcm.size) {
                    write(track, chunk.pcm, offset, chunk.pcm.size - offset)
                }
            }

            is AvSyncCoordinator.Decision.InsertSilence -> {
                correctedChunks++
                val silence = ByteArray(decision.frames * format.bytesPerFrame)
                write(track, silence, 0, silence.size)
                write(track, chunk.pcm, 0, chunk.pcm.size)
            }

            AvSyncCoordinator.Decision.Discard -> discardedChunks++
        }
    }

    fun stop() {
        val current = track ?: return
        track = null
        runCatching {
            // A track that was still priming was never started, so pause/stop would throw.
            if (isPlaying) {
                current.pause()
                current.flush()
                current.stop()
            }
        }.onFailure { Log.e(TAG, "error stopping audio track: $it") }
        isPlaying = false
        current.release()
        if (discardedChunks > 0 || correctedChunks > 0) {
            Log.i(TAG, "audio session ended: $correctedChunks corrected, $discardedChunks discarded")
        }
    }

    /**
     * Writes the whole range, retrying on a short write.
     *
     * `WRITE_BLOCKING` can still return early (an interrupted write, a track transition).
     * Dropping the remainder loses the tail of a chunk, which is an audible click and, more
     * importantly, silently breaks the frame accounting the playout prediction rests on.
     */
    /** Starts playback once the priming cushion is buffered. */
    private fun startPlaybackIfPrimed(track: AudioTrack) {
        if (framesWritten < primingFrames) return
        track.play()
        isPlaying = true
        Log.i(TAG, "audio playback started after priming $framesWritten frames")
    }

    private fun write(track: AudioTrack, data: ByteArray, offset: Int, size: Int) {
        var position = offset
        var remaining = size
        while (remaining > 0) {
            val written = track.write(data, position, remaining, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                // Negative is an error; zero means no progress is being made, and looping
                // on it would spin forever.
                if (written < 0) Log.e(TAG, "AudioTrack.write failed: $written")
                return
            }
            framesWritten += written / format.bytesPerFrame
            position += written
            remaining -= written
        }
    }

    /**
     * When the next frame written will be heard, or null if that is not yet knowable.
     *
     * Only `getTimestamp` is used, because it is the only source on the same axis as the
     * video reference: it reports true device presentation time, including output latency.
     * It returns nothing until enough audio has played, and returning a buffer-fill
     * estimate in the meantime was worse than returning nothing — `playbackHeadPosition`
     * counts frames the mixer consumed and excludes downstream output latency (routinely
     * 20-80 ms), so the estimate reads as "audio is early", silence gets padded, and when
     * `getTimestamp` becomes available the padding shows up as lateness that is then
     * unwound by skipping. Every transition produced an audible correction in both
     * directions. Playing uncorrected until the real timestamp exists is strictly better.
     */
    private fun predictedPlayoutNanos(): Long? {
        val track = this.track ?: return null
        if (!track.getTimestamp(timestamp)) return null
        return predictor.predictFromTimestamp(
            framesWritten = framesWritten,
            reportedFramePosition = timestamp.framePosition,
            reportedNanos = timestamp.nanoTime,
            nowNanos = System.nanoTime(),
        )
    }
}
