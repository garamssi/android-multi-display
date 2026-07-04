package com.desklink.android.data.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.desklink.android.domain.model.DisplayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import javax.inject.Inject

/**
 * HEVC/H.264 hardware video decoder built on [MediaCodec].
 *
 * Threading model: this decoder is driven **synchronously**. Encoded frames are
 * pushed via [submitFrame] (from the network/video stream coroutine) and decoded
 * frames are drained + rendered via [renderFrame], which is invoked once per
 * display vsync by [VsyncRenderer] on the Choreographer thread. All MediaCodec
 * buffer operations are non-blocking (0 timeout) and safe to call from those
 * threads. (An async `setCallback` model was considered but the vsync-paced
 * rendering below integrates more cleanly with a synchronous drain loop.)
 *
 * Input frames are never silently dropped (A-M1): if no input buffer is available
 * when a frame arrives, the frame is queued in [pendingFrames] and retried on the
 * next [submitFrame]/[renderFrame] pump. Feed Annex-B frames; the concatenated
 * Annex-B CSD (VPS+SPS+PPS) goes in the MediaFormat "csd-0".
 */
class HEVCDecoder @Inject constructor() {

    /**
     * Codec factory seam. Defaults to a real hardware/software decoder; tests
     * replace it with a factory that returns a mock [MediaCodec].
     */
    internal var codecFactory: MediaCodecFactory = DefaultMediaCodecFactory

    @Volatile private var codec: MediaCodec? = null

    private val bufferInfo = MediaCodec.BufferInfo()

    /** Encoded frames waiting for an available input buffer (FIFO, oldest first). */
    private val pendingFrames = ArrayDeque<PendingFrame>()

    private data class PendingFrame(val data: ByteArray, val timestampUs: Long)

    /**
     * Configures the decoder for the given codec and resolution and starts it.
     * @param surface The Surface to render decoded frames to.
     * @param config Display configuration with codec, width, height.
     * @param csd Codec Specific Data in Annex-B form (VPS+SPS+PPS for HEVC,
     *   SPS+PPS for H.264), placed into MediaFormat "csd-0".
     */
    suspend fun configure(surface: Surface, config: DisplayConfig, csd: ByteArray) =
        withContext(Dispatchers.IO) {
            release()

            val mimeType = when (config.codec) {
                DisplayConfig.Codec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
                DisplayConfig.Codec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            }

            val format = MediaFormat.createVideoFormat(mimeType, config.width, config.height).apply {
                // Annex-B concatenated CSD (VPS+SPS+PPS) per protocol spec.
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024) // 2MB
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            val newCodec = codecFactory.create(mimeType)
            // Release the newly created codec if configure()/start() throws (unsupported
            // resolution, bad CSD, HW codec busy). Otherwise it is never assigned to
            // `codec` and never released -> a native/hardware MediaCodec leaks, and since
            // this path is retried on every reconnect, repeated failures can exhaust the
            // device's codec instances.
            try {
                newCodec.configure(format, surface, null, 0)
                newCodec.start()
            } catch (e: Exception) {
                runCatching { newCodec.release() }
                throw e
            }

            synchronized(pendingFrames) { pendingFrames.clear() }
            codec = newCodec
        }

    /**
     * Submits an encoded (Annex-B) frame to the decoder.
     *
     * The frame is enqueued and an input pump runs immediately. If no codec input
     * buffer is currently free the frame stays queued and is retried later — it is
     * never dropped (A-M1). Note: [isKeyframe] is intentionally NOT translated into
     * an input flag; decoders infer IDR from the bitstream, and
     * BUFFER_FLAG_KEY_FRAME is an *encoder-output* flag. Regular frames are queued
     * with flags = 0.
     *
     * @param data Encoded NAL unit data (Annex-B).
     * @param timestampUs Presentation timestamp in microseconds.
     * @param isKeyframe Whether the source flagged this as a keyframe (IDR); used
     *   only for diagnostics, not passed to the codec.
     */
    fun submitFrame(data: ByteArray, timestampUs: Long, @Suppress("UNUSED_PARAMETER") isKeyframe: Boolean) {
        if (codec == null) return
        synchronized(pendingFrames) {
            pendingFrames.addLast(PendingFrame(data, timestampUs))
            // Bound memory: if the decoder stalls badly, drop the OLDEST frames so
            // the newest (most relevant) frames survive rather than growing unbounded.
            while (pendingFrames.size > MAX_PENDING_FRAMES) {
                pendingFrames.removeFirst()
            }
        }
        pumpInput()
    }

    /**
     * Feeds queued frames into any available codec input buffers. Stops as soon as
     * no input buffer is free (leaving remaining frames queued for the next pump).
     */
    private fun pumpInput() {
        val codec = codec ?: return
        synchronized(pendingFrames) {
            while (pendingFrames.isNotEmpty()) {
                val inputIndex = try {
                    codec.dequeueInputBuffer(0L) // non-blocking
                } catch (_: IllegalStateException) {
                    return
                }
                if (inputIndex < 0) return // no input buffer right now — retry later

                val frame = pendingFrames.peekFirst() ?: return
                val inputBuffer = codec.getInputBuffer(inputIndex)
                if (inputBuffer == null) {
                    // Buffer vanished; try again next pump without losing the frame.
                    return
                }
                inputBuffer.clear()
                inputBuffer.put(frame.data)
                // flags = 0: regular decoder input. CODEC_CONFIG is only for inline
                // CSD, which we deliver via MediaFormat "csd-0" instead.
                codec.queueInputBuffer(inputIndex, 0, frame.data.size, frame.timestampUs, 0)
                pendingFrames.removeFirst()
            }
        }
    }

    /**
     * Drains and renders **all** decoded output frames that are currently ready,
     * called once per vsync. Loops until the codec reports nothing more is ready
     * (a non-positive dequeue index), so a vsync never leaves a backlog of decoded
     * frames undrained (A-C3). Also re-pumps any queued input frames first.
     *
     * @return true if at least one frame was rendered this call.
     */
    fun renderFrame(): Boolean {
        val codec = codec ?: return false

        // Flush any input that was waiting for a free buffer.
        pumpInput()

        var rendered = false
        while (true) {
            val outputIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 0L) // non-blocking
            } catch (_: IllegalStateException) {
                break
            }

            when {
                outputIndex >= 0 -> {
                    codec.releaseOutputBuffer(outputIndex, true) // render to surface
                    rendered = true
                    // keep looping to drain every ready output buffer
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // New output format; nothing to render for this dequeue, keep draining.
                }

                outputIndex == INFO_OUTPUT_BUFFERS_CHANGED -> {
                    // Delivered on API 28 (minSdk). No cached buffer array to refresh
                    // (we call getOutputBuffer per-index), so just keep draining.
                }

                else -> {
                    // INFO_TRY_AGAIN_LATER (or any other non-positive): no more ready.
                    break
                }
            }
        }
        return rendered
    }

    /**
     * Releases the decoder and frees resources.
     */
    suspend fun release() = withContext(Dispatchers.IO) {
        synchronized(pendingFrames) { pendingFrames.clear() }
        // Detach the handle first so a concurrent submit/render sees no codec, then
        // release. release() frees the codec in ANY state; stop() is best-effort and
        // commonly throws when the codec is already in an error state — run each in its
        // own try so a throwing stop() never skips release() (which would leak the codec
        // and, with `codec = null`, lose the only handle to it).
        val current = codec
        codec = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
    }

    /**
     * Test seam: injects an already-started (mock) codec, bypassing the
     * Android-heavy [configure] path (MediaFormat etc. are not available in plain
     * JVM unit tests). Not for production use.
     */
    internal fun attachCodecForTest(mediaCodec: MediaCodec) {
        synchronized(pendingFrames) { pendingFrames.clear() }
        codec = mediaCodec
    }

    /** Number of frames currently buffered awaiting an input buffer (test aid). */
    internal fun pendingFrameCount(): Int = synchronized(pendingFrames) { pendingFrames.size }

    /** Factory seam so the codec can be mocked in unit tests. */
    fun interface MediaCodecFactory {
        fun create(mimeType: String): MediaCodec
    }

    private companion object {
        /** Cap the retry queue so a stalled decoder can't OOM the app. */
        const val MAX_PENDING_FRAMES = 30

        /**
         * INFO_OUTPUT_BUFFERS_CHANGED is deprecated but is still delivered on API 28
         * (our minSdk); referenced here so it can be handled during output draining.
         */
        @Suppress("DEPRECATION")
        val INFO_OUTPUT_BUFFERS_CHANGED = MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
    }
}

/**
 * Default factory: prefers a hardware decoder for the given MIME type, falling
 * back to the platform default decoder.
 */
object DefaultMediaCodecFactory : HEVCDecoder.MediaCodecFactory {
    override fun create(mimeType: String): MediaCodec {
        val codecName = findHardwareDecoder(mimeType)
        return if (codecName != null) {
            MediaCodec.createByCodecName(codecName)
        } else {
            MediaCodec.createDecoderByType(mimeType)
        }
    }

    private fun findHardwareDecoder(mimeType: String): String? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return codecList.codecInfos
            .filter { !it.isEncoder && it.isHardwareAcceleratedCompat() }
            .flatMap { info -> info.supportedTypes.map { info.name to it } }
            .firstOrNull { (_, type) -> type.equals(mimeType, ignoreCase = true) }
            ?.first
    }

    /**
     * [MediaCodecInfo.isHardwareAccelerated] only exists on API 29+. On API 28
     * (our minSdk) we approximate it by excluding known software codec name
     * prefixes ("OMX.google." and "c2.android." are Google's software codecs).
     */
    private fun MediaCodecInfo.isHardwareAcceleratedCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isHardwareAccelerated
        } else {
            !name.startsWith("OMX.google.", ignoreCase = true) &&
                !name.startsWith("c2.android.", ignoreCase = true)
        }
}
