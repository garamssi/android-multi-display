package com.desklink.android.data

import com.desklink.android.data.network.PacketFramer
import com.desklink.android.domain.model.MessageType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PacketFramerTest {

    @Test
    fun `frame creates valid packet`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val packet = PacketFramer.frame(MessageType.PING, payload)

        // Total: 4 (length) + 1 (type) + 3 (payload) = 8
        assertEquals(8, packet.size)

        // Length field (big-endian): 4 = 1 (type) + 3 (payload)
        assertEquals(0, packet[0].toInt())
        assertEquals(0, packet[1].toInt())
        assertEquals(0, packet[2].toInt())
        assertEquals(4, packet[3].toInt())

        // Type byte
        assertEquals(MessageType.PING, packet[4])

        // Payload
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
}
