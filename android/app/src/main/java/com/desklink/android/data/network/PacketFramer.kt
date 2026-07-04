package com.desklink.android.data.network

import com.desklink.android.domain.model.ProtocolConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles packet framing/unframing per protocol spec:
 * [4 bytes length (uint32 BE)] [1 byte type] [payload]
 * Length = size of (type + payload)
 */
object PacketFramer {

    /**
     * Frames a message into a wire-format packet.
     */
    fun frame(type: Byte, payload: ByteArray): ByteArray {
        val length = 1 + payload.size // type byte + payload
        val buffer = ByteBuffer.allocate(4 + length).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(length)
        buffer.put(type)
        buffer.put(payload)
        return buffer.array()
    }

    /**
     * Result of attempting to unframe a packet from a buffer.
     */
    sealed interface UnframeResult {
        data class Success(val type: Byte, val payload: ByteArray, val consumed: Int) : UnframeResult
        data object NeedMoreData : UnframeResult
        data class Error(val message: String) : UnframeResult
    }

    /**
     * Attempts to unframe one packet from the given buffer.
     * @param buffer Raw received data
     * @param offset Starting offset in buffer
     * @param length Available bytes from offset
     * @return UnframeResult
     */
    fun unframe(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): UnframeResult {
        // Need at least 5 bytes (4 length + 1 type)
        if (length < 5) return UnframeResult.NeedMoreData

        val bb = ByteBuffer.wrap(buffer, offset, length).order(ByteOrder.BIG_ENDIAN)
        // Read the 4-byte length field as an unsigned 32-bit value into a Long.
        val packetLength = bb.int.toLong() and 0xFFFFFFFFL

        // Length must cover at least the 1-byte type field.
        if (packetLength < 1L) return UnframeResult.Error("Invalid packet: length must be >= 1")
        // Reject anything above the 4MB cap BEFORE any .toInt() conversion so we never
        // attempt to allocate a huge / negative ByteArray from a corrupt length field.
        if (packetLength > ProtocolConstants.MAX_PACKET_SIZE.toLong()) {
            return UnframeResult.Error("Packet too large: $packetLength bytes")
        }

        // All size math done in Long to avoid Int overflow / sign issues.
        val totalSize = 4L + packetLength
        if (length.toLong() < totalSize) return UnframeResult.NeedMoreData

        // Safe: packetLength is guaranteed in [1, MAX_PACKET_SIZE], so these fit in Int.
        val consumed = totalSize.toInt()
        val type = bb.get()
        val payloadSize = packetLength.toInt() - 1
        val payload = ByteArray(payloadSize)
        bb.get(payload)

        return UnframeResult.Success(type = type, payload = payload, consumed = consumed)
    }
}
