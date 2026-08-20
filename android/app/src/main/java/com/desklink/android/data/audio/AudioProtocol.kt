package com.desklink.android.data.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsers for Audio-channel payloads (AUDIO_CONFIG 0x30, AUDIO_FRAME 0x31).
 *
 * Header fields are Big-Endian like the rest of the protocol. The PCM block is NOT
 * byte-swapped: it is opaque, and AUDIO_CONFIG declares it as signed 16-bit
 * little-endian precisely so it can be handed to `AudioTrack` (whose
 * `ENCODING_PCM_16BIT` is native-endian) with no per-sample work here.
 *
 * Payloads are the *unframed* message bodies — the outer [PacketFramer] length+type has
 * already been stripped.
 */
object AudioProtocol {

    /** AUDIO_CONFIG payload length: SampleRate(4) + Channels(1) + BitsPerSample(1) + Encoding(1). */
    const val CONFIG_SIZE = 7

    /** Fixed AUDIO_FRAME header length: Timestamp(8) + FrameCount(4). */
    const val FRAME_HEADER_SIZE = 12

    /** Encoding byte for interleaved signed 16-bit little-endian PCM. */
    const val ENCODING_PCM_S16LE: Byte = 0x01

    private const val BITS_PER_BYTE = 8
    private const val MICROS_PER_SECOND = 1_000_000L

    /** Bit depth implied by [ENCODING_PCM_S16LE]; cross-checked against the wire value. */
    private const val PCM_S16LE_BITS = 16

    /**
     * PCM layout announced by AUDIO_CONFIG.
     *
     * Only constructible with playable values — [parseConfig] rejects a zero sample rate
     * or channel count, so nothing downstream has to defend against a zero divisor.
     */
    data class AudioFormat(
        val sampleRate: Int,
        val channelCount: Int,
        val bitsPerSample: Int,
    ) {
        /** Bytes in one sample frame (one sample for every channel). Never zero. */
        val bytesPerFrame: Int = channelCount * bitsPerSample / BITS_PER_BYTE

        /**
         * Playback duration of [frameCount] sample frames, in microseconds.
         *
         * Truncating division: at 44.1 kHz this loses a fraction of a microsecond per
         * call, so a playback POSITION must be derived from a running frame count rather
         * than by summing these values (about 41 ppm, ~25 ms over ten minutes).
         */
        fun durationUs(frameCount: Long): Long = frameCount * MICROS_PER_SECOND / sampleRate
    }

    /**
     * One decoded AUDIO_FRAME.
     *
     * [timestampUs] is on the SAME axis as [VideoProtocol.VideoFrame.timestampUs] — host
     * uptime microseconds on the Mac. That shared axis is what makes lip-sync possible.
     *
     * Note: [pcm] is a [ByteArray], so generated equals/hashCode use array identity.
     * Fields are read positionally; do not rely on structural equality.
     */
    data class AudioChunk(
        val timestampUs: Long,
        val frameCount: Int,
        val pcm: ByteArray,
    ) {
        /**
         * Whether the declared frame count matches the PCM actually carried. Must be
         * checked before [frameCount] is used for playback timing: a corrupt frame could
         * otherwise claim any duration and skew the playout prediction.
         */
        fun isConsistentWith(format: AudioFormat): Boolean =
            pcm.size.toLong() == frameCount.toLong() * format.bytesPerFrame
    }

    /**
     * Parses AUDIO_CONFIG (0x30). Returns null for a short payload, an unknown encoding,
     * a bit depth contradicting that encoding, or an unplayable rate/channel count.
     */
    fun parseConfig(payload: ByteArray): AudioFormat? {
        if (payload.size < CONFIG_SIZE) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

        // uint32 read into a Long so a rate above Int.MAX_VALUE cannot appear negative.
        val sampleRate = buffer.int.toLong() and 0xFFFF_FFFFL
        val channelCount = buffer.get().toInt() and 0xFF
        val bitsPerSample = buffer.get().toInt() and 0xFF
        val encoding = buffer.get()

        if (encoding != ENCODING_PCM_S16LE) return null
        if (bitsPerSample != PCM_S16LE_BITS) return null
        if (sampleRate <= 0 || sampleRate > Int.MAX_VALUE) return null
        if (channelCount <= 0) return null

        return AudioFormat(
            sampleRate = sampleRate.toInt(),
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
        )
    }

    /**
     * Parses AUDIO_FRAME (0x31). Returns null for a truncated header or a frame carrying
     * no PCM — legal framing, but nothing to play, and letting it through would feed a
     * zero-length write into the sync bookkeeping.
     */
    fun parseFrame(payload: ByteArray): AudioChunk? {
        if (payload.size <= FRAME_HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

        val timestampUs = buffer.long
        val frameCount = buffer.int
        // A frame count that cannot describe this payload is refused here rather than
        // being caught downstream. Read as a signed Int, a large uint32 arrives negative,
        // and a huge positive value made `frameCount * bytesPerFrame` overflow so the
        // consistency check passed and the per-chunk correction cap became meaningless —
        // a 17-byte frame could then claim a billion sample frames and trigger a
        // multi-second blocking write of silence.
        val pcmSize = payload.size - FRAME_HEADER_SIZE
        if (frameCount <= 0 || frameCount > pcmSize) return null

        val pcm = ByteArray(pcmSize)
        buffer.get(pcm)

        return AudioChunk(timestampUs = timestampUs, frameCount = frameCount, pcm = pcm)
    }
}
