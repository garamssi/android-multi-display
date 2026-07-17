package com.desklink.android.data

import com.desklink.android.data.input.InputRepositoryImpl
import com.desklink.android.data.network.TCPClient
import com.desklink.android.domain.model.TouchEvent
import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.transport.Transport
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * A touch write can fail with "broken pipe" when the peer (Mac) went away mid-gesture
 * (stop / USB drop / reconnect) — `isConnected` only reflects the local socket. Touch
 * input is best-effort, so such an I/O failure must be dropped, NOT thrown: otherwise
 * it escapes the fire-and-forget coroutine that sent the touch and crashes the app.
 */
class InputRepositoryImplTest {

    private val transport = object : Transport {
        override suspend fun host() = "127.0.0.1"
        override fun controlPort() = ProtocolConstants.PORT_CONTROL
        override fun videoPort() = ProtocolConstants.PORT_VIDEO
        override fun inputPort() = ProtocolConstants.PORT_INPUT
    }

    private fun touch() = TouchEvent(
        action = TouchEvent.Action.DOWN,
        x = 0f,
        y = 0f,
        pressure = 0.toUShort(),
        pointerId = 0.toUByte(),
        timestampUs = 0L,
    )

    @Test
    fun `sendTouchEvent does not throw when the socket write fails with broken pipe`() = runTest {
        val client = mockk<TCPClient>(relaxed = true)
        every { client.isConnected } returns true
        coEvery { client.send(any(), any()) } throws java.net.SocketException("Broken pipe")

        val repo = InputRepositoryImpl(client, transport)

        // Must complete normally (touch dropped), not propagate the SocketException.
        repo.sendTouchEvent(touch())

        coVerify { client.send(any(), any()) }
    }

    @Test
    fun `sendTouchBatch does not throw when the socket was closed concurrently`() = runTest {
        val client = mockk<TCPClient>(relaxed = true)
        every { client.isConnected } returns true
        coEvery { client.send(any(), any()) } throws IllegalStateException("Not connected")

        val repo = InputRepositoryImpl(client, transport)

        repo.sendTouchBatch(listOf(touch(), touch()))

        coVerify { client.send(any(), any()) }
    }

    @Test
    fun `does not send when not connected`() = runTest {
        val client = mockk<TCPClient>(relaxed = true)
        every { client.isConnected } returns false

        val repo = InputRepositoryImpl(client, transport)

        repo.sendTouchEvent(touch())

        coVerify(exactly = 0) { client.send(any(), any()) }
    }
}
