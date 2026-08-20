package com.desklink.android.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionStateTest {

    private val config = DisplayConfig()

    @Test
    fun `in-progress covers exactly the transient phases`() {
        assertTrue(ConnectionState.Connecting.isInProgress)
        assertTrue(ConnectionState.Handshaking.isInProgress)
        assertTrue(ConnectionState.Negotiating(config).isInProgress)
        assertTrue(ConnectionState.Reconnecting.isInProgress)

        assertFalse(ConnectionState.Disconnected.isInProgress)
        assertFalse(ConnectionState.Connected(config, "Mac").isInProgress)
        assertFalse(ConnectionState.Error(ConnectionError.LOST).isInProgress)
    }

    @Test
    fun `connected is only the live streaming state`() {
        assertTrue(ConnectionState.Connected(config, "Mac").isConnected)

        listOf(
            ConnectionState.Disconnected,
            ConnectionState.Connecting,
            ConnectionState.Handshaking,
            ConnectionState.Negotiating(config),
            ConnectionState.Reconnecting,
            ConnectionState.Error(ConnectionError.LOST),
        ).forEach { assertFalse(it.isConnected, "$it should not be connected") }
    }

    @Test
    fun `terminal covers exactly idle and error (leave the mirror)`() {
        assertTrue(ConnectionState.Disconnected.isTerminal)
        assertTrue(ConnectionState.Error(ConnectionError.LOST).isTerminal)

        assertFalse(ConnectionState.Connecting.isTerminal)
        assertFalse(ConnectionState.Handshaking.isTerminal)
        assertFalse(ConnectionState.Negotiating(config).isTerminal)
        assertFalse(ConnectionState.Connected(config, "Mac").isTerminal)
        assertFalse(ConnectionState.Reconnecting.isTerminal)
    }

    @Test
    fun `the three groupings are mutually exclusive and total`() {
        val all = listOf(
            ConnectionState.Disconnected,
            ConnectionState.Connecting,
            ConnectionState.Handshaking,
            ConnectionState.Negotiating(config),
            ConnectionState.Connected(config, "Mac"),
            ConnectionState.Error(ConnectionError.LOST),
            ConnectionState.Reconnecting,
        )
        all.forEach { state ->
            val hits = listOf(state.isInProgress, state.isConnected, state.isTerminal).count { it }
            assertEquals(1, hits, "$state must belong to exactly one grouping")
        }
    }
}
