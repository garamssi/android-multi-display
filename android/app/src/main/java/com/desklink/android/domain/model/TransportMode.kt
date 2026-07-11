package com.desklink.android.domain.model

/**
 * How the client reaches the Mac server.
 *
 * [USB] tunnels loopback over `adb reverse`; the physical link is the trust boundary,
 * so it stays plaintext and unauthenticated (unchanged behavior).
 *
 * [LAN] dials the Mac's IP directly on the same network. It is encrypted (TLS with a
 * self-signed cert, TOFU-pinned) and authenticated by PIN pairing (see
 * docs/WIFI_TRANSPORT_DESIGN.md); an unpaired or wrong-PIN client is rejected. It is
 * still an explicit opt-in with a trusted-network warning in the UI.
 */
enum class TransportMode { USB, LAN }
