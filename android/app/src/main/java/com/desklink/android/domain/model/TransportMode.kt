package com.desklink.android.domain.model

/**
 * How the client reaches the Mac server.
 *
 * [USB] tunnels loopback over `adb reverse`; the physical link is the trust boundary,
 * so it stays plaintext and unauthenticated (unchanged behavior).
 *
 * [LAN] dials the Mac's IP directly on the same network. In this phase LAN is
 * plaintext and unauthenticated and is therefore development-only, gated behind an
 * explicit opt-in with a warning in the UI. TLS + pairing land in a later phase
 * (see docs/WIFI_TRANSPORT_DESIGN.md), after which LAN becomes safe for real use.
 */
enum class TransportMode { USB, LAN }
