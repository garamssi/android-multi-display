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

class HEVCDecoder @Inject constructor() {

    internal var codecFactory: MediaCodecFactory = DefaultMediaCodecFactory

    @Volatile private var codec: MediaCodec? = null

    private val bufferInfo = MediaCodec.BufferInfo()

    private val pendingFrames = ArrayDeque<PendingFrame>()

    /**
     * Invoked for each frame as it is released to the Surface, with the frame's server
     * timestamp (microseconds, host uptime on the Mac) and the local `System.nanoTime`
     * at which it was rendered.
     *
     * Used to align audio with the picture; see `AvSyncCoordinator`. Nullable and
     * unset by default so the video path has no dependency on audio being present.
     */
    @Volatile
    var onFrameRendered: ((serverTimestampUs: Long, localNanos: Long) -> Unit)? = null

    /**
     * The picture's real size, taken from the decoded bitstream.
     *
     * The handshake resolution is what the Mac was asked for; mirror captures its own
     * screen's size instead, and nothing on the wire announces that. Without this the view
     * would stretch the picture to the panel's shape.
     */
    @Volatile
    var onDecodedSizeChanged: ((width: Int, height: Int) -> Unit)? = null

    private data class PendingFrame(val data: ByteArray, val timestampUs: Long)

    suspend fun configure(surface: Surface, config: DisplayConfig, csd: ByteArray) =
        withContext(Dispatchers.IO) {
            release()

            val mimeType = when (config.codec) {
                DisplayConfig.Codec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
                DisplayConfig.Codec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            }

            val format = MediaFormat.createVideoFormat(mimeType, config.width, config.height).apply {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024) // 2MB
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            val newCodec = codecFactory.create(mimeType)
            // Release newCodec if configure()/start() throws, else the MediaCodec leaks and reconnect retries exhaust the device's codec instances.
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

    // isKeyframe is not passed to the codec: decoders infer IDR from the bitstream, and BUFFER_FLAG_KEY_FRAME is an encoder-output flag.
    fun submitFrame(data: ByteArray, timestampUs: Long, @Suppress("UNUSED_PARAMETER") isKeyframe: Boolean) {
        if (codec == null) return
        synchronized(pendingFrames) {
            pendingFrames.addLast(PendingFrame(data, timestampUs))
            while (pendingFrames.size > MAX_PENDING_FRAMES) {
                pendingFrames.removeFirst()
            }
        }
        pumpInput()
    }

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
                    return
                }
                inputBuffer.clear()
                inputBuffer.put(frame.data)
                codec.queueInputBuffer(inputIndex, 0, frame.data.size, frame.timestampUs, 0)
                pendingFrames.removeFirst()
            }
        }
    }

    fun renderFrame(frameTimeNanos: Long, vsyncPeriodNanos: Long): Boolean {
        val codec = codec ?: return false

        pumpInput()

        var rendered = false
        // Server timestamp of the newest frame released in this pass, or null if none.
        var lastRenderedTimestampUs: Long? = null
        var releasedThisPass = 0
        while (true) {
            val outputIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 0L) // non-blocking
            } catch (_: IllegalStateException) {
                break
            }

            when {
                outputIndex >= 0 -> {
                    lastRenderedTimestampUs = bufferInfo.presentationTimeUs
                    codec.releaseOutputBuffer(outputIndex, true) // render to surface
                    rendered = true
                    releasedThisPass++
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    reportDecodedSize(codec)
                }

                outputIndex == INFO_OUTPUT_BUFFERS_CHANGED -> {
                }

                else -> {
                    break
                }
            }
        }
        // Report ONE observation per drain pass, for the newest frame. Every buffer
        // released here goes to the Surface, but only the last one is the picture the
        // viewer sees, and they would all be stamped with the same local time — so
        // reporting each of them would feed the sync reference a burst of observations
        // describing a render that never happened that way.
        // Reported against the VSYNC time, not System.nanoTime(). The frame released here
        // is scanned out at the next vsync, and frameTimeNanos is that cadence's own
        // clock: sampling wall time inside the drain loop instead folds this callback's
        // scheduling jitter straight into the lip-sync reference.
        // SurfaceFlinger consumes one queued buffer per vsync, so when a drain pass
        // releases several the newest is scanned out that many vsyncs later — reporting
        // +1 would understate video latency exactly when the decoder is behind, and the
        // sync path reads that as "audio is late" and speeds up for no reason.
        lastRenderedTimestampUs?.let {
            onFrameRendered?.invoke(it, frameTimeNanos + releasedThisPass * vsyncPeriodNanos)
        }
        return rendered
    }

    // The bitstream is the only place the real picture size is: VIDEO_CONFIG carries the
    // codec and CSD but no dimensions, and the size requested in the handshake is what the
    // Mac was ASKED for, not what it captured -- mirror captures its own screen's size.
    private fun reportDecodedSize(codec: MediaCodec) {
        val format = try {
            codec.outputFormat
        } catch (_: IllegalStateException) {
            return
        }
        // The crop rectangle is the picture; KEY_WIDTH/KEY_HEIGHT can be the padded macroblock
        // size, which would letterbox against a slightly wrong shape.
        val width = if (format.containsKey(KEY_CROP_RIGHT) && format.containsKey(KEY_CROP_LEFT)) {
            format.getInteger(KEY_CROP_RIGHT) - format.getInteger(KEY_CROP_LEFT) + 1
        } else {
            format.getInteger(MediaFormat.KEY_WIDTH)
        }
        val height = if (format.containsKey(KEY_CROP_BOTTOM) && format.containsKey(KEY_CROP_TOP)) {
            format.getInteger(KEY_CROP_BOTTOM) - format.getInteger(KEY_CROP_TOP) + 1
        } else {
            format.getInteger(MediaFormat.KEY_HEIGHT)
        }
        if (width > 0 && height > 0) onDecodedSizeChanged?.invoke(width, height)
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        synchronized(pendingFrames) { pendingFrames.clear() }
        // Detach handle before release so a concurrent submit/render sees no codec; run stop() and release() in separate try blocks so a throwing stop() never skips release().
        val current = codec
        codec = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
    }

    internal fun attachCodecForTest(mediaCodec: MediaCodec) {
        synchronized(pendingFrames) { pendingFrames.clear() }
        codec = mediaCodec
    }

    internal fun pendingFrameCount(): Int = synchronized(pendingFrames) { pendingFrames.size }

    fun interface MediaCodecFactory {
        fun create(mimeType: String): MediaCodec
    }

    private companion object {

        const val MAX_PENDING_FRAMES = 30

        // MediaFormat crop keys; public constants only exist from API 33, and minSdk is 28.
        const val KEY_CROP_LEFT = "crop-left"
        const val KEY_CROP_RIGHT = "crop-right"
        const val KEY_CROP_TOP = "crop-top"
        const val KEY_CROP_BOTTOM = "crop-bottom"

        // Deprecated but still delivered on API 28 (minSdk).
        @Suppress("DEPRECATION")
        val INFO_OUTPUT_BUFFERS_CHANGED = MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
    }
}

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

    // isHardwareAccelerated exists only on API 29+; on API 28 approximate by excluding Google software codec name prefixes.
    private fun MediaCodecInfo.isHardwareAcceleratedCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isHardwareAccelerated
        } else {
            !name.startsWith("OMX.google.", ignoreCase = true) &&
                !name.startsWith("c2.android.", ignoreCase = true)
        }
}
