package com.desklink.android.presentation.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.domain.repository.UsbStateMonitor
import com.desklink.android.domain.usecase.ConnectToServerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Connection screen. On [connect] it reads the user-selected
 * [com.desklink.android.domain.model.DisplayConfig] from [SettingsRepository] and
 * passes it to [ConnectToServerUseCase.connect] (A-L4), then exposes the shared
 * [ConnectionState] so the UI can react (e.g. navigate on Connected).
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val connectToServer: ConnectToServerUseCase,
    private val settingsRepository: SettingsRepository,
    usbStateMonitor: UsbStateMonitor,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectToServer.connectionState

    /** Whether a USB data link to a host is present, for the home-screen indicator. */
    val usbConnected: StateFlow<Boolean> = usbStateMonitor.usbConnected()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun connect() {
        viewModelScope.launch {
            val config = settingsRepository.current()
            connectToServer.connect(config)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            connectToServer.disconnect()
        }
    }
}
