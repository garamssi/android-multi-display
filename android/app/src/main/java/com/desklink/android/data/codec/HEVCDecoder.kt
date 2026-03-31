package com.desklink.android.data.codec

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import com.desklink.android.domain.model.DisplayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * HEVC/H.264 hardware decoder using MediaCodec async callback API.
 */
class HEVCDecoder @Inject constructor() {
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var isConfigured = false

    /**
     * Configures the decoder for the given codec and resolution.
     * @param surface The Surface to render decoded frames to
     * @param config Display configuration with codec, width, height
     * @param csd Codec Specific Data (VPS+SPS+PPS for HEVC, SPS+PPS for H.264)
     */
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

            // Find hardware decoder
            val codecName = findHardwareDecoder(mimeType)
            val newCodec = if (codecName != null) {
                MediaCodec.createByCodecName(codecName)
            } else {
                MediaCodec.createDecoderByType(mimeType)
            }

            newCodec.configure(format, surface, null, 0)
            newCodec.start()

            codec = newCodec
            isConfigured = true
        }

    /**
     * Submits an encoded frame to the decoder.
     * @param data Encoded NAL unit data
     * @param timestampUs Presentation timestamp in microseconds
     * @param isKeyframe Whether this is a keyframe (IDR)
     */
    fun submitFrame(data: ByteArray, timestampUs: Long, isKeyframe: Boolean) {
        val codec = codec ?: return

        val inputIndex = codec.dequeueInputBuffer(10_000) // 10ms timeout
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(data)

            var flags = 0
            if (isKeyframe) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME

            codec.queueInputBuffer(inputIndex, 0, data.size, timestampUs, flags)
        }
    }

    /**
     * Dequeues and renders decoded output frames.
     * Call this in a loop to keep rendering.
     * @return true if a frame was rendered, false if no frame available
     */
    fun renderFrame(): Boolean {
        val codec = codec ?: return false

        val bufferInfo = MediaCodec.BufferInfo()
        val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 1_000) // 1ms timeout

        return when {
            outputIndex >= 0 -> {
                // Render to surface (releaseOutputBuffer with render=true)
                codec.releaseOutputBuffer(outputIndex, true)
                true
            }
            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                // Format changed, continue
                false
            }
            else -> false
        }
    }

    /**
     * Releases the decoder and frees resources.
     */
    suspend fun release() = withContext(Dispatchers.IO) {
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        isConfigured = false
    }

    private fun findHardwareDecoder(mimeType: String): String? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return codecList.codecInfos
            .filter { !it.isEncoder && it.isHardwareAccelerated }
            .flatMap { info -> info.supportedTypes.map { info.name to it } }
            .firstOrNull { (_, type) -> type.equals(mimeType, ignoreCase = true) }
            ?.first
    }
}
