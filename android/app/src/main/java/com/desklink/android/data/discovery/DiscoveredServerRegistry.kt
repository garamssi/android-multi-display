package com.desklink.android.data.discovery

import com.desklink.android.domain.transport.DiscoveredServer

class DiscoveredServerRegistry {

    private val byName = LinkedHashMap<String, DiscoveredServer>()

    @Synchronized
    fun upsert(server: DiscoveredServer): List<DiscoveredServer> {
        byName[server.name] = server
        return snapshot()
    }

    @Synchronized
    fun remove(name: String): List<DiscoveredServer> {
        byName.remove(name)
        return snapshot()
    }

    @Synchronized
    fun snapshot(): List<DiscoveredServer> =
        byName.values.sortedBy { it.name.lowercase() }
}
