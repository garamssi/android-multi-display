package com.desklink.android.domain.model

object ProtocolConstants {
    const val PROTOCOL_VERSION = 1

    // Ports
    const val PORT_CONTROL = 7100
    const val PORT_VIDEO = 7101
    const val PORT_INPUT = 7102

    // Packet limits
    const val MAX_PACKET_SIZE = 4 * 1024 * 1024 // 4MB

    // Timing (ms)
    const val HANDSHAKE_TIMEOUT = 5_000L
    const val PING_INTERVAL = 1_000L
    const val PING_TIMEOUT = 3_000L
    // Live reconnect after a lost session. Over USB a drop is usually terminal
    // (cable pulled, Mac stopped) or a brief hiccup, so retries use a short fixed
    // interval and a low attempt cap: ~5s total before giving up and returning to
    // the Connect screen, rather than a long exponential backoff.
    const val RECONNECT_DELAY = 1_000L
    const val RECONNECT_MAX_ATTEMPTS = 5
    const val STREAM_START_TIMEOUT = 3_000L

    // Socket buffer
    const val SOCKET_BUFFER_SIZE = 2 * 1024 * 1024 // 2MB
}
