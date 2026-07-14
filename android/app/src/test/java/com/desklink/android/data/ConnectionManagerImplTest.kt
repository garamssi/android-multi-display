package com.desklink.android.data

import app.cash.turbine.test
import com.desklink.android.data.network.ConnectionManagerImpl
import com.desklink.android.data.network.HandshakeClient
import com.desklink.android.data.network.TCPClient
import com.desklink.android.domain.model.ConnectionError
import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.MessageType
import com.desklink.android.data.security.PairingAuth
import com.desklink.android.data.security.PairingCrypto
import com.desklink.android.data.security.PairingKeyProvider
import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.transport.Transport
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerImplTest {

    private val config = DisplayConfig()

    private fun fakeHandshakeClient(): HandshakeClient {
        val hs = mockk<HandshakeClient>()
        every { hs.buildHandshakeRequest(any(), any(), any()) } returns ByteArray(0)
        every { hs.buildConfigRequest(any()) } returns ByteArray(0)
        every { hs.parseHandshakeResponse(any()) } returns
            HandshakeClient.HandshakeResult.Accepted("Mac", "1.0.0")
        every { hs.parseConfigResponse(any()) } returns config
        return hs
    }

    private fun fakeTransport() = object : Transport {
        override suspend fun host() = "127.0.0.1"
        override fun controlPort() = ProtocolConstants.PORT_CONTROL
        override fun videoPort() = ProtocolConstants.PORT_VIDEO
        override fun inputPort() = ProtocolConstants.PORT_INPUT
    }

    private fun noAuth() = object : PairingKeyProvider {
        override fun currentKey(): ByteArray? = null
    }

    private fun withKey(key: ByteArray) = object : PairingKeyProvider {
        override fun currentKey(): ByteArray = key
    }

    private fun mockClient(receive: () -> Flow<Pair<Byte, ByteArray>>): TCPClient {
        val client = mockk<TCPClient>(relaxed = true)
        coEvery { client.connect(any(), any()) } returns Unit
        coEvery { client.send(any(), any()) } returns Unit
        coEvery { client.disconnect() } returns Unit
        every { client.receivePackets() } answers { receive() }
        return client
    }

    @Test
    fun `successful handshake transitions to Connected`() = runTest {
        val hs = fakeHandshakeClient()
        val client = mockClient {
            flow {
                emit(MessageType.HANDSHAKE_RESPONSE to ByteArray(0))
                emit(MessageType.CONFIG_RESPONSE to ByteArray(0))
                emit(MessageType.START_STREAM to ByteArray(0))
            }
        }
        val manager = ConnectionManagerImpl(hs, client, fakeTransport(), noAuth())
        manager.managerScope = backgroundScope

        manager.connectionState.test {
            assertEquals(ConnectionState.Disconnected, awaitItem())
            launch { manager.connect(config) }

            assertEquals(ConnectionState.Connecting, awaitItem())
            assertTrue(awaitItem() is ConnectionState.Handshaking)
            assertTrue(awaitItem() is ConnectionState.Negotiating)
            val connected = awaitItem()
            assertTrue(connected is ConnectionState.Connected, "got $connected")
            assertEquals("Mac", (connected as ConnectionState.Connected).serverName)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `handshake timeout transitions to Error TIMEOUT and closes socket`() = runTest {
        val hs = fakeHandshakeClient()
        val client = mockClient { flow { kotlinx.coroutines.awaitCancellation() } }
        val manager = ConnectionManagerImpl(hs, client, fakeTransport(), noAuth())
        manager.managerScope = backgroundScope

        val job = launch { manager.connect(config) }

        runCurrent()
        advanceTimeBy(ProtocolConstants.HANDSHAKE_TIMEOUT + 100)
        advanceUntilIdle()
        job.join()

        val state = manager.connectionState.value
        assertEquals(ConnectionState.Error(ConnectionError.TIMEOUT), state)
        coVerify { client.disconnect() }
    }

    @Test
    fun `protocol mismatch maps to Error PROTOCOL_MISMATCH`() = runTest {
        val hs = fakeHandshakeClient()
        every { hs.parseHandshakeResponse(any()) } returns
            HandshakeClient.HandshakeResult.Failed(ConnectionError.PROTOCOL_MISMATCH)
        val client = mockClient {
            flow { emit(MessageType.HANDSHAKE_RESPONSE to ByteArray(0)) }
        }
        val manager = ConnectionManagerImpl(hs, client, fakeTransport(), noAuth())
        manager.managerScope = backgroundScope

        manager.connect(config)
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Error(ConnectionError.PROTOCOL_MISMATCH),
            manager.connectionState.value,
        )
    }

    @Test
    fun `losing an established connection retries then gives up with Error LOST`() = runTest {
        val hs = fakeHandshakeClient()
        var connectCalls = 0
        var rxCalls = 0
        val client = mockk<TCPClient>(relaxed = true)
        coEvery { client.send(any(), any()) } returns Unit
        coEvery { client.disconnect() } returns Unit
        coEvery { client.connect(any(), any()) } answers {
            connectCalls++
            if (connectCalls >= 2) throw java.io.IOException("no server")
        }
        every { client.receivePackets() } answers {
            rxCalls++
            when (rxCalls) {
                1 -> flow {
                    emit(MessageType.HANDSHAKE_RESPONSE to ByteArray(0))
                    emit(MessageType.CONFIG_RESPONSE to ByteArray(0))
                    emit(MessageType.START_STREAM to ByteArray(0))
                }
                else -> flow<Pair<Byte, ByteArray>> { throw java.io.IOException("lost") }
            }
        }
        val manager = ConnectionManagerImpl(hs, client, fakeTransport(), noAuth())
        manager.managerScope = backgroundScope

        manager.connect(config)
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Error(ConnectionError.LOST),
            manager.connectionState.value,
        )
        coVerify(exactly = 1 + ProtocolConstants.RECONNECT_MAX_ATTEMPTS) { client.connect(any(), any()) }
    }

    @Test
    fun `losing an established connection recovers on a later attempt`() = runTest {
        val hs = fakeHandshakeClient()
        var rxCalls = 0
        val client = mockk<TCPClient>(relaxed = true)
        coEvery { client.connect(any(), any()) } returns Unit
        coEvery { client.send(any(), any()) } returns Unit
        coEvery { client.disconnect() } returns Unit
        every { client.receivePackets() } answers {
            rxCalls++
            when (rxCalls) {
                1 -> handshakeSuccessFlow()
                2 -> flow<Pair<Byte, ByteArray>> { throw java.io.IOException("lost") }
                3 -> handshakeSuccessFlow() // reconnect attempt succeeds
                else -> flow { kotlinx.coroutines.awaitCancellation() } // stay connected
            }
        }
        val manager = ConnectionManagerImpl(hs, client, fakeTransport(), noAuth())
        manager.managerScope = backgroundScope

        manager.connect(config)
        // Bounded advance (not advanceUntilIdle): the keep-alive never goes idle.
        advanceTimeBy(ProtocolConstants.RECONNECT_DELAY + 500)
        runCurrent()

        assertTrue(
            manager.connectionState.value is ConnectionState.Connected,
            "expected Connected, got ${manager.connectionState.value}",
        )
        coVerify(exactly = 2) { client.connect(any(), any()) }
    }

    @Test
    fun `disconnect during an active reconnect stays Disconnected with no further attempts`() = runTest {
        val hs = fakeHandshakeClient()
        var rxCalls = 0
        val client = mockk<TCPClient>(relaxed = true)
        coEvery { client.send(any(), any()) } returns Unit
        coEvery { client.disconnect() } returns Unit
        coEvery { client.connect(any(), any()) } returns Unit
        every { client.receivePackets() } answers {
            rxCalls++
            when (rxCalls) {
                1 -> handshakeSuccessFlow() // initial Connected
                2 -> flow<Pair<Byte, ByteArray>> { throw java.io.IOException("lost") } // drop -> reconnect
                else -> flow { kotlinx.coroutines.awaitCancellation() }
            }
        }
        val manager = ConnectionManagerImpl(hs, client, fakeTransport(), noAuth())
        manager.managerScope = backgroundScope

        manager.connect(config)
        runCurrent() // Connected -> lost -> reconnect loop now waiting in its delay
        assertTrue(
            manager.connectionState.value is ConnectionState.Reconnecting,
            "expected Reconnecting, got ${manager.connectionState.value}",
        )

        manager.disconnect()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
        coVerify(exactly = 1) { client.connect(any(), any()) }
    }

    @Test
    fun `connect after disconnect establishes a single fresh session`() = runTest {
        val hs = fakeHandshakeClient()
        var rxCalls = 0
        val client = mockk<TCPClient>(relaxed = true)
        coEvery { client.send(any(), any()) } returns Unit
        coEvery { client.disconnect() } returns Unit
        coEvery { client.connect(any(), any()) } returns Unit
        every { client.receivePackets() } answers {
            rxCalls++
            if (rxCalls % 2 == 1) handshakeSuccessFlow() else flow { kotlinx.coroutines.awaitCancellation() }
        }
        val manager = ConnectionManagerImpl(hs, client, fakeTransport(), noAuth())
        manager.managerScope = backgroundScope

        manager.connect(config)
        runCurrent()
        assertTrue(manager.connectionState.value is ConnectionState.Connected)

        manager.disconnect()
        runCurrent()
        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)

        manager.connect(config)
        runCurrent()
        assertTrue(manager.connectionState.value is ConnectionState.Connected)
        coVerify(exactly = 2) { client.connect(any(), any()) }
    }

    @Test
    fun `LAN pairing auth succeeds then the handshake connects`() = runTest {
        val hs = fakeHandshakeClient()
        val key = PairingCrypto.derivePsk("123456")
        val serverNonce = ByteArray(ProtocolConstants.AUTH_NONCE_LENGTH) { it.toByte() }
        val sent = mutableListOf<Pair<Byte, ByteArray>>()
        val client = mockk<TCPClient>(relaxed = true)
        coEvery { client.connect(any(), any()) } returns Unit
        coEvery { client.disconnect() } returns Unit
        coEvery { client.send(any(), any()) } answers {
            sent.add(firstArg<Byte>() to secondArg<ByteArray>()); Unit
        }
        var rxCalls = 0
        every { client.receivePackets() } answers {
            rxCalls++
            if (rxCalls == 1) {
                flow {
                    emit(MessageType.AUTH_CHALLENGE to serverNonce)
                    val response = sent.first { it.first == MessageType.AUTH_RESPONSE }.second
                    val clientNonce = response.copyOfRange(0, ProtocolConstants.AUTH_NONCE_LENGTH)
                    emit(MessageType.AUTH_CONFIRM to PairingAuth.serverProof(key, serverNonce, clientNonce))
                }
            } else {
                handshakeSuccessFlow()
            }
        }
        val manager = ConnectionManagerImpl(hs, client, fakeTransport(), withKey(key))
        manager.managerScope = backgroundScope

        manager.connect(config)
        advanceUntilIdle()

        assertTrue(
            manager.connectionState.value is ConnectionState.Connected,
            "expected Connected, got ${manager.connectionState.value}",
        )
        val response = sent.first { it.first == MessageType.AUTH_RESPONSE }.second
        val clientNonce = response.copyOfRange(0, ProtocolConstants.AUTH_NONCE_LENGTH)
        val proof = response.copyOfRange(ProtocolConstants.AUTH_NONCE_LENGTH, response.size)
        assertTrue(PairingAuth.verify(proof, PairingAuth.clientProof(key, serverNonce, clientNonce)))
    }

    @Test
    fun `LAN pairing auth failure surfaces Error PAIRING_REJECTED`() = runTest {
        val hs = fakeHandshakeClient()
        val key = PairingCrypto.derivePsk("123456")
        val serverNonce = ByteArray(ProtocolConstants.AUTH_NONCE_LENGTH) { it.toByte() }
        val client = mockk<TCPClient>(relaxed = true)
        coEvery { client.connect(any(), any()) } returns Unit
        coEvery { client.send(any(), any()) } returns Unit
        coEvery { client.disconnect() } returns Unit
        every { client.receivePackets() } answers {
            flow {
                emit(MessageType.AUTH_CHALLENGE to serverNonce)
                emit(MessageType.AUTH_CONFIRM to ByteArray(32)) // wrong server proof
            }
        }
        val manager = ConnectionManagerImpl(hs, client, fakeTransport(), withKey(key))
        manager.managerScope = backgroundScope

        manager.connect(config)
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Error(ConnectionError.PAIRING_REJECTED),
            manager.connectionState.value,
        )
    }

    @Test
    fun `LAN pairing read timeout (silent server) surfaces Error PAIRING_REJECTED`() = runTest {
        val hs = fakeHandshakeClient()
        val key = PairingCrypto.derivePsk("123456")
        val serverNonce = ByteArray(ProtocolConstants.AUTH_NONCE_LENGTH) { it.toByte() }
        val client = mockk<TCPClient>(relaxed = true)
        coEvery { client.connect(any(), any()) } returns Unit
        coEvery { client.send(any(), any()) } returns Unit
        coEvery { client.disconnect() } returns Unit
        every { client.receivePackets() } answers {
            flow {
                emit(MessageType.AUTH_CHALLENGE to serverNonce)
                throw java.net.SocketTimeoutException("read timed out")
            }
        }
        val manager = ConnectionManagerImpl(hs, client, fakeTransport(), withKey(key))
        manager.managerScope = backgroundScope

        manager.connect(config)
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Error(ConnectionError.PAIRING_REJECTED),
            manager.connectionState.value,
        )
    }

    private fun handshakeSuccessFlow(): Flow<Pair<Byte, ByteArray>> = flow {
        emit(MessageType.HANDSHAKE_RESPONSE to ByteArray(0))
        emit(MessageType.CONFIG_RESPONSE to ByteArray(0))
        emit(MessageType.START_STREAM to ByteArray(0))
    }
}
