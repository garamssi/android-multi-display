package com.desklink.android.domain.transport

import kotlinx.coroutines.flow.Flow

/**
 * Finds DeskLink Mac servers advertised on the local network (Wi-Fi/LAN), so the user
 * can pick one instead of typing an IP. The transport stays unaware of discovery: a
 * selected [DiscoveredServer]'s host is fed into the existing manual-host setting, which
 * `LanTransport` already dials.
 *
 * Implementations are data-layer (e.g. NsdManager). Collecting [servers] starts a scan;
 * cancelling the collection stops it and releases any OS resources (multicast lock).
 */
interface PeerDiscovery {
    /** Emits the current set of discovered servers, updated as they appear/disappear. */
    fun servers(): Flow<List<DiscoveredServer>>
}

/**
 * A DeskLink server found on the local network.
 *
 * @property name human-readable service name (defaults to the Mac's device name).
 * @property host resolved IPv4/host to dial.
 * @property port advertised control port (video/input use the fixed protocol ports).
 */
data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
)
