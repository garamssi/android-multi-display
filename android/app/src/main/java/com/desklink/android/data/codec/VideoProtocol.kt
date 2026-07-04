package com.desklink.android.data.codec

import com.desklink.android.domain.model.DisplayConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsers for Video-channel payloads (VIDEO_CONFIG 0x11, VIDEO_FRAME 0x10).
 *
 * All multibyte fields are Big-Endian. Payloads here are the *unframed* message
 * bodies (the outer [PacketFramer] length+type has already been stripped).
 */
object VideoProtocol {

    /** HEVC codec id per protocol spec. */
    const val CODEC_ID_HEVC: Byte = 0x01

    /** H.264 codec id per protocol spec. */
    const val CODEC_ID_H264: Byte = 0x02

    /** VIDEO_FRAME flag bit 0: this frame is a keyframe (IDR). */
    const val FLAG_KEYFRAME = 0x01

    /** VIDEO_FRAME flag bit 1: this frame carries a codec-config change. */
    const val FLAG_CONFIG = 0x02

    /** Fixed VIDEO_FRAME header length: Timestamp(8) + Flags(1) + FrameNumber(4). */
    const val FRAME_HEADER_SIZE = 13

    /**
     * Parsed VIDEO_CONFIG (0x11) payload:
     * `[CodecID u8][ConfigLength uint16 BE][ConfigData ...]`.
     *
     * Note: [csd] is a [ByteArray], so the generated [equals]/[hashCode] use array
     * *identity*, not content. Instances are consumed positionally (fields read
     * directly); do not rely on structural equality between two [VideoConfig]s.
     */
    data class VideoConfig(
        val codec: DisplayConfig.Codec,
        val csd: ByteArray,
    )

    /**
     * Parsed VIDEO_FRAME (0x10) header + NAL payload:
     * `[Timestamp int64 us BE][Flags u8][FrameNumber uint32 BE][NAL ...]`.
     *
     * Note: [nal] is a [ByteArray], so the generated [equals]/[hashCode] use array
     * *identity*, not content. Fields are read positionally; do not rely on
     * structural equality between two [VideoFrame]s.
     */
    data class VideoFrame(
        val timestampUs: Long,
        val isKeyframe: Boolean,
        val isConfig: Boolean,
        val frameNumber: Long,
        val nal: ByteArray,
    )

    /**
     * Parses a VIDEO_CONFIG payload. Returns null if malformed (too short or the
     * declared config length overruns the buffer).
     */
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

    /**
     * Parses a VIDEO_FRAME payload (13-byte header + NAL). Returns null if the
     * payload is shorter than the fixed header.
     */
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
