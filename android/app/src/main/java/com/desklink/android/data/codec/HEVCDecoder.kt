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

    fun renderFrame(): Boolean {
        val codec = codec ?: return false

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
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                }

                outputIndex == INFO_OUTPUT_BUFFERS_CHANGED -> {
                }

                else -> {
                    break
                }
            }
        }
        return rendered
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
