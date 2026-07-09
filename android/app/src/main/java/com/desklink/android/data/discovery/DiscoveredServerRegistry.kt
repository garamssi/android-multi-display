package com.desklink.android.data.discovery

import com.desklink.android.domain.transport.DiscoveredServer

/**
 * Thread-safe, pure in-memory set of currently-discovered servers, keyed by service
 * name so a re-resolve of the same service updates in place (no duplicates). Kept
 * separate from the Android NsdManager glue so the merge/dedup/order logic is unit
 * testable without the framework.
 */
class DiscoveredServerRegistry {

    private val byName = LinkedHashMap<String, DiscoveredServer>()

    /** Adds or updates [server] (by name) and returns the new sorted snapshot. */
    @Synchronized
    fun upsert(server: DiscoveredServer): List<DiscoveredServer> {
        byName[server.name] = server
        return snapshot()
    }

    /** Removes the server with [name] (if present) and returns the new snapshot. */
    @Synchronized
    fun remove(name: String): List<DiscoveredServer> {
        byName.remove(name)
        return snapshot()
    }

    /** Current servers, ordered by name (case-insensitive) for a stable UI list. */
    @Synchronized
    fun snapshot(): List<DiscoveredServer> =
        byName.values.sortedBy { it.name.lowercase() }
}
