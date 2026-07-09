package com.desklink.android.domain.model

object ProtocolConstants {
    const val PROTOCOL_VERSION = 1

    // Host the client dials. USB(adb reverse) tunnels the device's loopback to the Mac
    // server, so USB uses this default; the LAN transport supplies the Mac's IP at
    // runtime instead. Kept here so the value isn't hardcoded inside the socket client.
    const val LOOPBACK_HOST = "127.0.0.1"

    // Ports
    const val PORT_CONTROL = 7100
    const val PORT_VIDEO = 7101
    const val PORT_INPUT = 7102

    // Bonjour/NSD service type the Mac advertises on the control port over Wi-Fi (LAN).
    // Cross-platform contract with the macOS server's `bonjourServiceType`. Discovery
    // yields the Mac's host; the video/input ports remain the fixed constants above.
    // Note: NsdManager can be picky about a trailing dot across OS versions; this base
    // form works with discoverServices on current Android. Verify on-device if discovery
    // returns nothing.
    const val SERVICE_TYPE = "_desklink._tcp"

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
