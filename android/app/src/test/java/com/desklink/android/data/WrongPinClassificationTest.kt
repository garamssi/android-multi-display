package com.desklink.android.data

import com.desklink.android.data.device.ScreenMetricsProvider
import com.desklink.android.data.device.ScreenResolution
import com.desklink.android.data.network.ConnectionManagerImpl
import com.desklink.android.data.network.HandshakeClient
import com.desklink.android.data.network.TCPClient
import com.desklink.android.data.security.PairingCrypto
import com.desklink.android.data.security.PairingKeyProvider
import com.desklink.android.domain.model.ConnectionError
import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.MessageType
import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.transport.Transport
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// A Mac that rejects the proof sends nothing back (AuthGate.verifyResponse returns nil and
// ControlChannelUseCase only replies on success) while its PINGs keep arriving. So a wrong PIN
// looks exactly like silence, and the client has to name it a pairing failure -- otherwise the
// UI offers to retry the connection instead of asking for the new code.
@OptIn(ExperimentalCoroutinesApi::class)
class WrongPinClassificationTest {

    private val config = DisplayConfig(width = 1920, height = 1200, fps = 60)

    @Test
    fun `a server that never confirms the proof is reported as a pairing failure`() = runTest {
        val key = PairingCrypto.derivePsk("123456")
        val serverNonce = ByteArray(ProtocolConstants.AUTH_NONCE_LENGTH) { it.toByte() }

        val client = mockk<TCPClient>(relaxed = true)
        coEvery { client.connect(any(), any()) } returns Unit
        coEvery { client.send(any(), any()) } returns Unit
        coEvery { client.disconnect() } returns Unit
        every { client.receivePackets() } answers {
            flow {
                emit(MessageType.AUTH_CHALLENGE to serverNonce)
                // No AUTH_CONFIRM ever: the proof was wrong. The server keeps pinging, so the
                // socket is alive and only the pairing is stuck.
                while (true) {
                    kotlinx.coroutines.delay(ProtocolConstants.PING_INTERVAL)
                    emit(MessageType.PING to ByteArray(8))
                }
            }
        }

        val hs = mockk<HandshakeClient>(relaxed = true)
        every { hs.buildHandshakeRequest(any(), any(), any()) } returns ByteArray(0)

        val keys = mockk<PairingKeyProvider>()
        every { keys.currentKey() } returns key

        val manager = ConnectionManagerImpl(hs, client, transport(), keys, metrics())
        manager.managerScope = backgroundScope

        manager.connect(config)
        runCurrent()
        advanceTimeBy(ProtocolConstants.HANDSHAKE_TIMEOUT + 500)
        runCurrent()

        assertEquals(
            ConnectionState.Error(ConnectionError.PAIRING_REJECTED),
            manager.connectionState.value,
        )
    }

    // The server now says so instead of going quiet. The client must act on that immediately
    // rather than sitting out its handshake timeout with the picture of a hung connection.
    @Test
    fun `an ERROR during auth fails the pairing at once`() = runTest {
        val key = PairingCrypto.derivePsk("123456")
        val serverNonce = ByteArray(ProtocolConstants.AUTH_NONCE_LENGTH) { it.toByte() }

        val client = mockk<TCPClient>(relaxed = true)
        coEvery { client.connect(any(), any()) } returns Unit
        coEvery { client.send(any(), any()) } returns Unit
        coEvery { client.disconnect() } returns Unit
        every { client.receivePackets() } answers {
            flow {
                emit(MessageType.AUTH_CHALLENGE to serverNonce)
                emit(MessageType.ERROR to "{\"code\":1004}".toByteArray())
                kotlinx.coroutines.awaitCancellation()
            }
        }

        // The real parser: the point of this test is that the code on the wire is what
        // decides, and a relaxed mock would answer with a mock instead of an error code.
        val hs = HandshakeClient()

        val keys = mockk<PairingKeyProvider>()
        every { keys.currentKey() } returns key

        val manager = ConnectionManagerImpl(hs, client, transport(), keys, metrics())
        manager.managerScope = backgroundScope

        manager.connect(config)
        runCurrent()

        assertEquals(
            ConnectionState.Error(ConnectionError.PAIRING_REJECTED),
            manager.connectionState.value,
        )
        // The answer was on the wire, so the handshake timeout must not have been what
        // produced it -- otherwise the user waits five seconds to be told the code is wrong.
        assertTrue(
            testScheduler.currentTime < ProtocolConstants.HANDSHAKE_TIMEOUT,
            "took ${testScheduler.currentTime} ms; the ERROR was ignored and the timeout fired",
        )
    }

    // A lockout is not a wrong code. Collapsing the two sends the user to re-read a PIN that
    // was never the problem, while the thing that helps is waiting.
    @Test
    fun `a lockout is reported as a lockout, not as a wrong code`() = runTest {
        val key = PairingCrypto.derivePsk("123456")

        val client = mockk<TCPClient>(relaxed = true)
        coEvery { client.connect(any(), any()) } returns Unit
        coEvery { client.send(any(), any()) } returns Unit
        coEvery { client.disconnect() } returns Unit
        every { client.receivePackets() } answers {
            flow {
                // Locked out, so no challenge is issued at all -- only the reason.
                emit(MessageType.ERROR to "{\"code\":1005}".toByteArray())
                kotlinx.coroutines.awaitCancellation()
            }
        }

        val hs = HandshakeClient()
        val keys = mockk<PairingKeyProvider>()
        every { keys.currentKey() } returns key

        val manager = ConnectionManagerImpl(hs, client, transport(), keys, metrics())
        manager.managerScope = backgroundScope

        manager.connect(config)
        runCurrent()

        assertEquals(
            ConnectionState.Error(ConnectionError.PAIRING_LOCKED_OUT),
            manager.connectionState.value,
        )
    }

    private fun transport() = object : Transport {
        override suspend fun host() = "192.168.0.2"
        override fun controlPort() = 7110
        override fun videoPort() = 7111
        override fun inputPort() = 7112
        override fun audioPort(): Int? = null
    }

    private fun metrics() = object : ScreenMetricsProvider {
        override fun nativeResolution() = ScreenResolution(2560, 1600)
        override fun maxRefreshRate() = 60
    }
}
