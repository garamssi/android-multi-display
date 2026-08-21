package com.desklink.android.data

import com.desklink.android.data.device.ScreenMetricsProvider
import com.desklink.android.data.device.ScreenResolution
import com.desklink.android.data.network.ConnectionManagerImpl
import com.desklink.android.data.network.HandshakeClient
import com.desklink.android.data.network.PacketFramer
import com.desklink.android.data.network.TCPClient
import com.desklink.android.data.security.PairingKeyProvider
import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.MessageType
import com.desklink.android.domain.transport.Transport
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

// One socket, one byte stream. The production code read that stream with a fresh framed-packet
// reader per phase (auth, handshake, steady state); bytes a cancelled reader had already pulled
// off the socket were gone, so the stream desynchronized and nothing was received again.
//
// These tests model the stream faithfully -- a single InputStream shared by every
// receivePackets() call -- which the older fakes did not: they handed each call its own
// complete flow, which is what hid this.
@OptIn(ExperimentalCoroutinesApi::class)
class ControlChannelHandoverTest {

    private val config = DisplayConfig(width = 1920, height = 1200, fps = 60)

    @Test
    fun `a DISCONNECT arriving in the same read as the handshake still reaches the manager`() = runTest {
        // Everything the server sends, in one chunk: the handshake and then the goodbye. A
        // real server does exactly this when it restarts a session right after connecting.
        val stream = ByteArrayInputStream(
            PacketFramer.frame(MessageType.HANDSHAKE_RESPONSE, ByteArray(0)) +
                PacketFramer.frame(MessageType.CONFIG_RESPONSE, ByteArray(0)) +
                PacketFramer.frame(MessageType.START_STREAM, ByteArray(0)) +
                PacketFramer.frame(MessageType.DISCONNECT, ByteArray(0)),
        )

        val manager = managerReading(stream)
        manager.managerScope = backgroundScope
        manager.connect(config)
        runCurrent()

        // The invariant: never report a live session on a connection that has already said
        // goodbye. Which failure state it lands in is the caller's business.
        val state = manager.connectionState.value
        assertTrue(
            state !is ConnectionState.Connected,
            "DISCONNECT was dropped at the reader handover; state is $state",
        )
    }

    private fun managerReading(stream: java.io.InputStream): ConnectionManagerImpl {
        val framer = TCPClient(mockk(relaxed = true))
        val client = mockk<TCPClient>(relaxed = true)
        coEvery { client.connect(any(), any()) } returns Unit
        coEvery { client.send(any(), any()) } returns Unit
        coEvery { client.disconnect() } returns Unit
        // Every call reads the SAME stream, which is what a socket does.
        every { client.receivePackets() } answers { framer.framedPackets(stream) }

        val hs = mockk<HandshakeClient>()
        every { hs.buildHandshakeRequest(any(), any(), any()) } returns ByteArray(0)
        every { hs.buildConfigRequest(any()) } returns ByteArray(0)
        every { hs.parseHandshakeResponse(any()) } returns
            HandshakeClient.HandshakeResult.Accepted("Mac", "1.0.0")
        every { hs.parseConfigResponse(any()) } returns config

        val transport = object : Transport {
            override suspend fun host() = "127.0.0.1"
            override fun controlPort() = 7100
            override fun videoPort() = 7101
            override fun inputPort() = 7102
            override fun audioPort(): Int? = 7103
        }
        val keys = mockk<PairingKeyProvider>()
        every { keys.currentKey() } returns null

        val metrics = object : ScreenMetricsProvider {
            override fun nativeResolution() = ScreenResolution(2560, 1600)
            override fun maxRefreshRate() = 60
        }
        return ConnectionManagerImpl(hs, client, transport, keys, metrics)
    }
}
