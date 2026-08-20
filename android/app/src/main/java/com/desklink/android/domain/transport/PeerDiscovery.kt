package com.desklink.android.domain.transport

import kotlinx.coroutines.flow.Flow

interface PeerDiscovery {
    fun servers(): Flow<List<DiscoveredServer>>
}

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    val osVersion: String? = null,
)
