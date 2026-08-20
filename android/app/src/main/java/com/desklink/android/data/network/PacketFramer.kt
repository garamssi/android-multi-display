package com.desklink.android.data.network

import com.desklink.android.domain.model.ProtocolConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketFramer {

    fun frame(type: Byte, payload: ByteArray): ByteArray {
        val length = 1 + payload.size // type byte + payload
        val buffer = ByteBuffer.allocate(4 + length).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(length)
        buffer.put(type)
        buffer.put(payload)
        return buffer.array()
    }

    sealed interface UnframeResult {
        data class Success(val type: Byte, val payload: ByteArray, val consumed: Int) : UnframeResult
        data object NeedMoreData : UnframeResult
        data class Error(val message: String) : UnframeResult
    }

    fun unframe(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): UnframeResult {
        if (length < 5) return UnframeResult.NeedMoreData

        val bb = ByteBuffer.wrap(buffer, offset, length).order(ByteOrder.BIG_ENDIAN)
        val packetLength = bb.int.toLong() and 0xFFFFFFFFL

        if (packetLength < 1L) return UnframeResult.Error("Invalid packet: length must be >= 1")
        // Reject lengths above the cap BEFORE any .toInt() so a corrupt length never allocates a huge/negative ByteArray.
        if (packetLength > ProtocolConstants.MAX_PACKET_SIZE.toLong()) {
            return UnframeResult.Error("Packet too large: $packetLength bytes")
        }

        val totalSize = 4L + packetLength
        if (length.toLong() < totalSize) return UnframeResult.NeedMoreData

        val consumed = totalSize.toInt()
        val type = bb.get()
        val payloadSize = packetLength.toInt() - 1
        val payload = ByteArray(payloadSize)
        bb.get(payload)

        return UnframeResult.Success(type = type, payload = payload, consumed = consumed)
    }
}
