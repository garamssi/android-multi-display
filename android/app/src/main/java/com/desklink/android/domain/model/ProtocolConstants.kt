package com.desklink.android.domain.model

object ProtocolConstants {
    const val PROTOCOL_VERSION = 1

    const val LOOPBACK_HOST = "127.0.0.1"

    const val PORT_CONTROL = 7100
    const val PORT_VIDEO = 7101
    const val PORT_INPUT = 7102
    const val PORT_AUDIO = 7103

    const val PORT_CONTROL_LAN = 7110
    const val PORT_VIDEO_LAN = 7111
    const val PORT_INPUT_LAN = 7112
    const val PORT_AUDIO_LAN = 7113

    // NsdManager can be picky about a trailing dot across OS versions; keep this base form (no trailing dot).
    const val SERVICE_TYPE = "_desklink._tcp"

    const val TXT_KEY_OS = "os"

    const val PSK_HKDF_SALT = "desklink-pairing-v1"
    const val PSK_HKDF_INFO = "desklink-psk"
    const val PSK_LENGTH_BYTES = 32
    const val PSK_IDENTITY = "desklink"
    const val PAIRING_PIN_LENGTH = 6

    const val AUTH_NONCE_LENGTH = 16
    const val AUTH_CLIENT_CONTEXT = "desklink-auth-client"
    const val AUTH_SERVER_CONTEXT = "desklink-auth-server"

    const val MAX_PACKET_SIZE = 4 * 1024 * 1024 // 4MB

    const val HANDSHAKE_TIMEOUT = 5_000L
    const val PING_INTERVAL = 1_000L
    const val PING_TIMEOUT = 3_000L
    // Waits before each reconnect attempt. Exponential rather than flat because the two
    // cases it serves are far apart: the server restarting its own session is back in a few
    // hundred milliseconds, while a device that has to re-enumerate over USB takes seconds.
    // A flat one-second wait paid the slow case's price on every fast one. The total window
    // is unchanged, so nothing is given up to get the first retry in early.
    val RECONNECT_DELAYS_MS = listOf(200L, 400L, 800L, 1_600L, 2_000L)

    // Derived, not declared: two numbers that must agree is one that goes stale.
    val RECONNECT_MAX_ATTEMPTS = RECONNECT_DELAYS_MS.size
    const val STREAM_START_TIMEOUT = 3_000L

    const val SOCKET_BUFFER_SIZE = 2 * 1024 * 1024 // 2MB
}
