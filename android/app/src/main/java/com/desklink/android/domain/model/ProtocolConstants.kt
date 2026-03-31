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
    const val RECONNECT_DELAY = 1_000L
    const val RECONNECT_MAX_DELAY = 30_000L
    const val RECONNECT_MAX_ATTEMPTS = 10
    const val STREAM_START_TIMEOUT = 3_000L

    // Socket buffer
    const val SOCKET_BUFFER_SIZE = 2 * 1024 * 1024 // 2MB
}
