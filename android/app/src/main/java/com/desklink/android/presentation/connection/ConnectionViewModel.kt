package com.desklink.android.presentation.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.domain.model.TransportMode
import com.desklink.android.domain.repository.UsbStateMonitor
import com.desklink.android.domain.transport.DiscoveredServer
import com.desklink.android.domain.transport.PeerDiscovery
import com.desklink.android.domain.usecase.ConnectToServerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val connectToServer: ConnectToServerUseCase,
    private val settingsRepository: SettingsRepository,
    private val peerDiscovery: PeerDiscovery,
    usbStateMonitor: UsbStateMonitor,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectToServer.connectionState

    val transportMode: StateFlow<TransportMode> = settingsRepository.transportMode

    val lastConnectedHost: StateFlow<String> = settingsRepository.lastConnectedHost

    val usbConnected: StateFlow<Boolean> = usbStateMonitor.usbConnected()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

    private var discoveryJob: Job? = null

    fun connect() {
        viewModelScope.launch {
            // The 180-flip stays tablet-side and is not sent; only the rotation-oriented dims go to the Mac.
            val stored = settingsRepository.current().oriented(settingsRepository.currentDisplayRotation())
            val config = if (settingsRepository.currentTransportMode() == TransportMode.LAN) {
                stored.copy(bitrateKbps = stored.bitrateKbps.coerceAtMost(LAN_MAX_BITRATE_KBPS))
            } else {
                stored
            }
            connectToServer.connect(config)
        }
    }

    fun connectTo(server: DiscoveredServer, pin: String) = connectToHost(server.host, pin)

    fun connectToManual(host: String, pin: String) = connectToHost(host.trim(), pin)

    private fun connectToHost(host: String, pin: String) {
        settingsRepository.setManualHost(host)
        settingsRepository.setPairingPin(pin)
        settingsRepository.setLastConnectedHost(host)
        connect()
    }

    fun disconnect() {
        viewModelScope.launch {
            connectToServer.disconnect()
        }
    }

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            peerDiscovery.servers().collect { _discoveredServers.value = it }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _discoveredServers.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }

    private companion object {
        const val LAN_MAX_BITRATE_KBPS = 20_000
    }
}
