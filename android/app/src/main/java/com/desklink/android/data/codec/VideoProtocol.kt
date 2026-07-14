package com.desklink.android.data.codec

import com.desklink.android.domain.model.DisplayConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VideoProtocol {

    const val CODEC_ID_HEVC: Byte = 0x01

    const val CODEC_ID_H264: Byte = 0x02

    const val FLAG_KEYFRAME = 0x01

    const val FLAG_CONFIG = 0x02

    const val FRAME_HEADER_SIZE = 13

    data class VideoConfig(
        val codec: DisplayConfig.Codec,
        val csd: ByteArray,
    )

    data class VideoFrame(
        val timestampUs: Long,
        val isKeyframe: Boolean,
        val isConfig: Boolean,
        val frameNumber: Long,
        val nal: ByteArray,
    )

    fun parseConfig(payload: ByteArray): VideoConfig? {
        if (payload.size < 3) return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val codecId = bb.get()
        val configLength = bb.short.toInt() and 0xFFFF
        if (bb.remaining() < configLength) return null
        val csd = ByteArray(configLength)
        bb.get(csd)
        val codec = when (codecId) {
            CODEC_ID_HEVC -> DisplayConfig.Codec.HEVC
            CODEC_ID_H264 -> DisplayConfig.Codec.H264
            else -> return null
        }
        return VideoConfig(codec = codec, csd = csd)
    }

    fun parseFrame(payload: ByteArray): VideoFrame? {
        if (payload.size < FRAME_HEADER_SIZE) return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val timestampUs = bb.long
        val flags = bb.get().toInt() and 0xFF
        val frameNumber = bb.int.toLong() and 0xFFFFFFFFL
        val nal = ByteArray(bb.remaining())
        bb.get(nal)
        return VideoFrame(
            timestampUs = timestampUs,
            isKeyframe = (flags and FLAG_KEYFRAME) != 0,
            isConfig = (flags and FLAG_CONFIG) != 0,
            frameNumber = frameNumber,
            nal = nal,
        )
    }
}
