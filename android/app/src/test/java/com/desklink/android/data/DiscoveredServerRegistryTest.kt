package com.desklink.android.data

import com.desklink.android.data.discovery.DiscoveredServerRegistry
import com.desklink.android.domain.transport.DiscoveredServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The registry is the pure merge/dedup/order logic behind NsdManager discovery: a
 * re-resolve of the same service updates in place (keyed by name), removals drop it,
 * and the snapshot is name-sorted for a stable UI list.
 */
class DiscoveredServerRegistryTest {

    private fun server(name: String, host: String, port: Int = 7100) =
        DiscoveredServer(name = name, host = host, port = port)

    @Test
    fun `upsert adds and re-resolve of the same name updates in place`() {
        val registry = DiscoveredServerRegistry()

        registry.upsert(server("Mac", "192.168.0.5"))
        val afterUpdate = registry.upsert(server("Mac", "192.168.0.9"))

        assertEquals(1, afterUpdate.size)
        assertEquals("192.168.0.9", afterUpdate.first().host)
    }

    @Test
    fun `snapshot is sorted by name case-insensitively`() {
        val registry = DiscoveredServerRegistry()

        registry.upsert(server("zeta", "10.0.0.3"))
        registry.upsert(server("Alpha", "10.0.0.1"))
        val list = registry.upsert(server("beta", "10.0.0.2"))

        assertEquals(listOf("Alpha", "beta", "zeta"), list.map { it.name })
    }

    @Test
    fun `remove drops a server and leaves the rest`() {
        val registry = DiscoveredServerRegistry()
        registry.upsert(server("Mac-A", "10.0.0.1"))
        registry.upsert(server("Mac-B", "10.0.0.2"))

        val list = registry.remove("Mac-A")

        assertEquals(listOf("Mac-B"), list.map { it.name })
    }

    @Test
    fun `removing an unknown name is a no-op`() {
        val registry = DiscoveredServerRegistry()
        registry.upsert(server("Mac", "10.0.0.1"))

        val list = registry.remove("Nope")

        assertEquals(1, list.size)
    }
}
