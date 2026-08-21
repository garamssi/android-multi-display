package com.desklink.android.domain.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    data object Handshaking : ConnectionState

    data class Negotiating(val config: DisplayConfig) : ConnectionState

    data class Connected(
        val config: DisplayConfig,
        val serverName: String,
        // Announced by the server. Mirror refuses input, so the UI must gate touch and say
        // why — an unresponsive screen with no explanation reads as a broken app.
        val displayMode: DisplayMode = DisplayMode.DEFAULT,
        // Also the server's choice: this Mac's screen is not this panel's shape, and which
        // price to pay for that -- bars or cropped edges -- is set on the Mac.
        val videoScaling: VideoScaling = VideoScaling.DEFAULT,
    ) : ConnectionState

    data class Error(val error: ConnectionError) : ConnectionState

    data object Reconnecting : ConnectionState

    val isInProgress: Boolean
        get() = this is Connecting || this is Handshaking || this is Negotiating || this is Reconnecting

    val isConnected: Boolean
        get() = this is Connected

    val isTerminal: Boolean
        get() = this is Disconnected || this is Error
}
