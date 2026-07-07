package com.desklink.android.data

import com.desklink.android.data.network.KeepAliveController
import com.desklink.android.domain.model.MessageType
import com.desklink.android.domain.model.ProtocolConstants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeepAliveControllerTest {

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    @Test
    fun `ping payload matches golden vector`() {
        val ka = KeepAliveController(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            send = { _, _ -> },
            onConnectionLost = {},
        )
        // int64 BE ms: 1700000000000
        assertEquals("0000018BCFE56800", ka.timestampPayload(1_700_000_000_000L).toHex())
    }

    @Test
    fun `sends PING every interval while PONGs arrive`() = runTest {
        val sent = mutableListOf<Pair<Byte, ByteArray>>()
        val ka = KeepAliveController(
            scope = this,
            send = { type, payload -> sent.add(type to payload) },
            onConnectionLost = { throw AssertionError("must not be lost while ponging") },
            clock = { testScheduler.currentTime },
        )
        ka.start()

        // Advance 3 intervals, feeding a PONG after each so lastPong stays fresh.
        repeat(3) {
            advanceTimeBy(ProtocolConstants.PING_INTERVAL)
            runCurrent()
            ka.onPacket(MessageType.PONG, ka.timestampPayload(testScheduler.currentTime))
        }

        val pings = sent.count { it.first == MessageType.PING }
        assertEquals(3, pings, "expected one PING per interval")
        ka.stop()
    }

    @Test
    fun `declares connection lost when no PONG within timeout`() = runTest {
        var lost = false
        val ka = KeepAliveController(
            scope = this,
            send = { _, _ -> },
            onConnectionLost = { lost = true },
            clock = { testScheduler.currentTime },
        )
        ka.start()

        // No PONG ever. After PING_TIMEOUT elapses, the watchdog must fire.
        advanceTimeBy(ProtocolConstants.PING_TIMEOUT + ProtocolConstants.PING_INTERVAL)
        runCurrent()

        assertTrue(lost, "connection should be declared lost after PONG timeout")
        ka.stop()
    }

    @Test
    fun `replies PONG to inbound PING echoing timestamp`() = runTest {
        val sent = mutableListOf<Pair<Byte, ByteArray>>()
        val ka = KeepAliveController(
            scope = this,
            send = { type, payload -> sent.add(type to payload) },
            onConnectionLost = {},
            clock = { testScheduler.currentTime },
        )

        val incoming = ka.timestampPayload(1_700_000_000_000L)
        val handled = ka.onPacket(MessageType.PING, incoming)

        assertTrue(handled)
        assertEquals(1, sent.size)
        assertEquals(MessageType.PONG, sent[0].first)
        assertEquals(incoming.toHex(), sent[0].second.toHex())
    }

    @Test
    fun `onPacket returns false for non keepalive messages`() = runTest {
        val ka = KeepAliveController(
            scope = this,
            send = { _, _ -> },
            onConnectionLost = {},
        )
        assertFalse(ka.onPacket(MessageType.VIDEO_FRAME, ByteArray(0)))
    }
}
