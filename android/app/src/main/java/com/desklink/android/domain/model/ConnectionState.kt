package com.desklink.android.domain.model

/**
 * The single connection state exposed by the connection layer and observed by the UI.
 *
 * State CLASSIFICATION lives here (the [isInProgress] / [isConnected] / [isTerminal]
 * groupings) so every screen derives the same meaning from one place, instead of each
 * screen re-listing the cases (which let the Connection and Display screens drift).
 */
sealed interface ConnectionState {
    /** Idle: no connection and none being attempted. */
    data object Disconnected : ConnectionState

    /** Opening the control socket / pairing. */
    data object Connecting : ConnectionState

    /** Control socket up; awaiting the handshake response. */
    data object Handshaking : ConnectionState

    /** Handshake accepted; negotiating the display config. */
    data class Negotiating(val config: DisplayConfig) : ConnectionState

    /** Live: streaming with the negotiated [config] from [serverName]. */
    data class Connected(val config: DisplayConfig, val serverName: String) : ConnectionState

    /** Terminal failure carrying the cause. */
    data class Error(val error: ConnectionError) : ConnectionState

    /** A live session was lost and is being re-established (bounded retry loop). */
    data object Reconnecting : ConnectionState

    /**
     * A transient, in-progress phase: the connection is being established or
     * re-established and the UI should show a busy/reconnecting indicator.
     */
    val isInProgress: Boolean
        get() = this is Connecting || this is Handshaking || this is Negotiating || this is Reconnecting

    /** The live streaming state. */
    val isConnected: Boolean
        get() = this is Connected

    /**
     * A terminal state where the mirror should be left and the user returned to the
     * connect screen: idle (disconnected) or errored.
     */
    val isTerminal: Boolean
        get() = this is Disconnected || this is Error
}
