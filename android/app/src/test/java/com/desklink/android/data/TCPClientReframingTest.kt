package com.desklink.android.data

import com.desklink.android.data.network.PacketFramer
import com.desklink.android.data.network.PacketFramingException
import com.desklink.android.data.network.TCPClient
import com.desklink.android.data.security.PlaintextSecureChannel
import com.desklink.android.domain.model.MessageType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class TCPClientReframingTest {

    private class ChunkedInputStream(data: ByteArray, private val chunk: Int) : InputStream() {
        private val delegate = ByteArrayInputStream(data)
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, minOf(len, chunk))
    }

    @Test
    fun `reassembles multiple frames delivered one byte at a time`() = runTest {
        val p1 = PacketFramer.frame(MessageType.PING, byteArrayOf(1, 2, 3))
        val p2 = PacketFramer.frame(MessageType.PONG, byteArrayOf(4, 5))
        val p3 = PacketFramer.frame(MessageType.VIDEO_FRAME, ByteArray(200) { it.toByte() })
        val stream = ChunkedInputStream(p1 + p2 + p3, chunk = 1)

        val packets = TCPClient(PlaintextSecureChannel()).framedPackets(stream).toList()

        assertEquals(3, packets.size)
        assertEquals(MessageType.PING, packets[0].first)
        assertArrayEquals(byteArrayOf(1, 2, 3), packets[0].second)
        assertEquals(MessageType.PONG, packets[1].first)
        assertArrayEquals(byteArrayOf(4, 5), packets[1].second)
        assertEquals(MessageType.VIDEO_FRAME, packets[2].first)
        assertArrayEquals(ByteArray(200) { it.toByte() }, packets[2].second)
    }

    @Test
    fun `reassembles a large frame split across many reads`() = runTest {
        val bigPayload = ByteArray(512 * 1024) { (it % 251).toByte() }
        val packet = PacketFramer.frame(MessageType.VIDEO_FRAME, bigPayload)
        val stream = ChunkedInputStream(packet, chunk = 7000)

        val packets = TCPClient(PlaintextSecureChannel()).framedPackets(stream).toList()

        assertEquals(1, packets.size)
        assertEquals(MessageType.VIDEO_FRAME, packets[0].first)
        assertArrayEquals(bigPayload, packets[0].second)
    }

    @Test
    fun `mixed small and large frames in arbitrary chunking`() = runTest {
        val frames = listOf(
            PacketFramer.frame(MessageType.PING, ByteArray(0)),
            PacketFramer.frame(MessageType.VIDEO_CONFIG, ByteArray(1000) { it.toByte() }),
            PacketFramer.frame(MessageType.PONG, byteArrayOf(9)),
            PacketFramer.frame(MessageType.VIDEO_FRAME, ByteArray(300_000) { (it % 97).toByte() }),
        )
        val combined = frames.reduce { acc, bytes -> acc + bytes }
        val stream = ChunkedInputStream(combined, chunk = 333)

        val packets = TCPClient(PlaintextSecureChannel()).framedPackets(stream).toList()

        assertEquals(4, packets.size)
        assertEquals(MessageType.PING, packets[0].first)
        assertEquals(0, packets[0].second.size)
        assertEquals(MessageType.VIDEO_CONFIG, packets[1].first)
        assertEquals(1000, packets[1].second.size)
        assertEquals(MessageType.PONG, packets[2].first)
        assertArrayEquals(byteArrayOf(9), packets[2].second)
        assertEquals(MessageType.VIDEO_FRAME, packets[3].first)
        assertEquals(300_000, packets[3].second.size)
    }

    @Test
    fun `framing error throws typed PacketFramingException`() = runTest {
        val badHeader = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x07)
        val stream = ChunkedInputStream(badHeader, chunk = 5)

        val thrown = runCatching { TCPClient(PlaintextSecureChannel()).framedPackets(stream).toList() }.exceptionOrNull()
        assertTrue(
            thrown is PacketFramingException,
            "expected PacketFramingException, got $thrown",
        )
    }
}
