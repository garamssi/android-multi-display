package com.desklink.android.domain.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Handshaking(val serverName: String) : ConnectionState
    data class Negotiating(val config: DisplayConfig) : ConnectionState
    data class Connected(val config: DisplayConfig, val serverName: String) : ConnectionState
    data class Error(val error: ConnectionError) : ConnectionState
    data object Reconnecting : ConnectionState
}
