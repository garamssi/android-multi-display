package com.desklink.android.domain.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    data object Handshaking : ConnectionState

    data class Negotiating(val config: DisplayConfig) : ConnectionState

    data class Connected(val config: DisplayConfig, val serverName: String) : ConnectionState

    data class Error(val error: ConnectionError) : ConnectionState

    data object Reconnecting : ConnectionState

    val isInProgress: Boolean
        get() = this is Connecting || this is Handshaking || this is Negotiating || this is Reconnecting

    val isConnected: Boolean
        get() = this is Connected

    val isTerminal: Boolean
        get() = this is Disconnected || this is Error
}
