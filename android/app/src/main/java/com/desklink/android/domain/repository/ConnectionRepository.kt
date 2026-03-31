package com.desklink.android.domain.repository

import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.domain.model.DisplayConfig
import kotlinx.coroutines.flow.StateFlow

interface ConnectionRepository {
    val connectionState: StateFlow<ConnectionState>
    suspend fun connect(config: DisplayConfig)
    suspend fun disconnect()
    suspend fun reconnect()
}
