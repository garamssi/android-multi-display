package com.desklink.android.data

import com.desklink.android.data.network.PacketFramer
import com.desklink.android.domain.model.MessageType
import com.desklink.android.domain.model.ProtocolConstants
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PacketFramerTest {

    @Test
    fun `frame creates valid packet`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val packet = PacketFramer.frame(MessageType.PING, payload)

        assertEquals(8, packet.size)

        assertEquals(0, packet[0].toInt())
        assertEquals(0, packet[1].toInt())
        assertEquals(0, packet[2].toInt())
        assertEquals(4, packet[3].toInt())

        assertEquals(MessageType.PING, packet[4])

        assertArrayEquals(payload, packet.copyOfRange(5, 8))
    }

    @Test
    fun `frame empty payload`() {
        val packet = PacketFramer.frame(MessageType.DISCONNECT, byteArrayOf())
        assertEquals(5, packet.size) // 4 header + 1 type
    }

    @Test
    fun `unframe valid packet`() {
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val packet = PacketFramer.frame(MessageType.PONG, payload)

        val result = PacketFramer.unframe(packet)
        assertTrue(result is PacketFramer.UnframeResult.Success)
        val success = result as PacketFramer.UnframeResult.Success
        assertEquals(MessageType.PONG, success.type)
        assertArrayEquals(payload, success.payload)
        assertEquals(packet.size, success.consumed)
    }

    @Test
    fun `unframe need more data`() {
        val result = PacketFramer.unframe(byteArrayOf(0x00, 0x00))
        assertTrue(result is PacketFramer.UnframeResult.NeedMoreData)
    }

    @Test
    fun `round trip all message types`() {
        val types = listOf(
            MessageType.HANDSHAKE_REQUEST,
            MessageType.VIDEO_FRAME,
            MessageType.TOUCH_EVENT,
            MessageType.BITRATE_UPDATE,
        )
        for (type in types) {
            val payload = ByteArray(50) { it.toByte() }
            val packet = PacketFramer.frame(type, payload)
            val result = PacketFramer.unframe(packet)

            assertTrue(result is PacketFramer.UnframeResult.Success, "Failed for type $type")
            val success = result as PacketFramer.UnframeResult.Success
            assertEquals(type, success.type)
            assertArrayEquals(payload, success.payload)
        }
    }

    private fun header(lengthField: Long): ByteArray = byteArrayOf(
        ((lengthField ushr 24) and 0xFF).toByte(),
        ((lengthField ushr 16) and 0xFF).toByte(),
        ((lengthField ushr 8) and 0xFF).toByte(),
        (lengthField and 0xFF).toByte(),
        MessageType.PING, // type byte so we have the required 5 header bytes
    )

    @Test
    fun `unframe accepts exactly 4MB packet length`() {
        val max = ProtocolConstants.MAX_PACKET_SIZE.toLong()
        val result = PacketFramer.unframe(header(max))
        assertTrue(
            result is PacketFramer.UnframeResult.NeedMoreData,
            "4MB length must be accepted (needs more data), got $result",
        )
    }

    @Test
    fun `unframe rejects 4MB plus one`() {
        val overMax = ProtocolConstants.MAX_PACKET_SIZE.toLong() + 1
        val result = PacketFramer.unframe(header(overMax))
        assertTrue(result is PacketFramer.UnframeResult.Error, "4MB+1 must be rejected")
    }

    @Test
    fun `unframe rejects length less than one`() {
        val result = PacketFramer.unframe(header(0L))
        assertTrue(result is PacketFramer.UnframeResult.Error, "length 0 must be rejected")
    }

    @Test
    fun `unframe rejects would-be-negative unsigned length`() {
        // 0xFFFFFFFF as signed Int is -1; as unsigned Long it exceeds MAX, must be rejected.
        val result = PacketFramer.unframe(header(0xFFFFFFFFL))
        assertTrue(
            result is PacketFramer.UnframeResult.Error,
            "0xFFFFFFFF length must be rejected as too large, got $result",
        )
    }

    @Test
    fun `unframe needs more data when body incomplete`() {
        val payload = ByteArray(100) { it.toByte() }
        val packet = PacketFramer.frame(MessageType.VIDEO_FRAME, payload)
        val result = PacketFramer.unframe(packet, 0, packet.size - 1)
        assertTrue(result is PacketFramer.UnframeResult.NeedMoreData)
    }
}
