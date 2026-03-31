package com.desklink.android.domain.usecase

import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.repository.ConnectionRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class ConnectToServerUseCase @Inject constructor(
    private val connectionRepository: ConnectionRepository,
) {
    val connectionState: StateFlow<ConnectionState>
        get() = connectionRepository.connectionState

    suspend fun connect(config: DisplayConfig = DisplayConfig()) {
        connectionRepository.connect(config)
    }

    suspend fun disconnect() {
        connectionRepository.disconnect()
    }
}
