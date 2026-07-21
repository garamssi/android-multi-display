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

/**
 * Drives the Connection screen. On [connect] it reads the user-selected
 * [com.desklink.android.domain.model.DisplayConfig] from [SettingsRepository] and passes
 * it to [ConnectToServerUseCase.connect] (A-L4), then exposes the shared
 * [ConnectionState] so the UI can react (e.g. navigate on Connected).
 *
 * The home screen is transport-aware: in USB mode it shows the single Connect action; in
 * Wi-Fi mode it surfaces [discoveredServers] (from [PeerDiscovery]) so the user picks a
 * Mac to connect to. The chosen server's host is written into settings and dialed by the
 * LAN transport — the connect flow itself is transport-agnostic.
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val connectToServer: ConnectToServerUseCase,
    private val settingsRepository: SettingsRepository,
    private val peerDiscovery: PeerDiscovery,
    usbStateMonitor: UsbStateMonitor,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectToServer.connectionState

    /** The selected transport, so the screen shows USB vs Wi-Fi (discovery) flows. */
    val transportMode: StateFlow<TransportMode> = settingsRepository.transportMode

    /** Host of the last-connected LAN server, so the list can flag it "RECENT". */
    val lastConnectedHost: StateFlow<String> = settingsRepository.lastConnectedHost

    /** Whether a USB data link to a host is present, for the home-screen indicator. */
    val usbConnected: StateFlow<Boolean> = usbStateMonitor.usbConnected()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Macs discovered on the LAN (empty until [startDiscovery] runs). */
    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

    private var discoveryJob: Job? = null

    fun connect() {
        viewModelScope.launch {
            // Orient the requested resolution for the chosen rotation (portrait sends tall
            // dims); the Mac builds the display in that geometry. The 180-flip part stays
            // tablet-side and is not sent.
            val stored = settingsRepository.current().oriented(settingsRepository.currentDisplayRotation())
            // Wi-Fi keeps the user-selected fps and the native (tablet) resolution, but caps
            // the bitrate. The auto bitrate for this resolution (~40Mbps) saturates a typical
            // Wi-Fi link, which buffers into multi-second freezes and starves the control
            // keep-alive; a moderate cap keeps 60fps smooth with only a small hit to
            // still-frame sharpness. USB is uncapped. On a strong 5GHz link this cap is rarely
            // the binding limit; bandwidth-limited users can still lower fps/resolution.
            val config = if (settingsRepository.currentTransportMode() == TransportMode.LAN) {
                stored.copy(bitrateKbps = stored.bitrateKbps.coerceAtMost(LAN_MAX_BITRATE_KBPS))
            } else {
                stored
            }
            connectToServer.connect(config)
        }
    }

    /** Pairs with a discovered Mac (PIN) and connects to it. */
    fun connectTo(server: DiscoveredServer, pin: String) = connectToHost(server.host, pin)

    /** Pairs with a manually entered Mac IP (PIN) and connects to it. */
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

    /** Begins LAN discovery (call only after the Wi-Fi permission is granted). Idempotent. */
    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            peerDiscovery.servers().collect { _discoveredServers.value = it }
        }
    }

    /** Stops LAN discovery and clears the list (releases the multicast lock). */
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
        /**
         * Wi-Fi bitrate cap (kbps). Keeps 60fps smooth on a shared/typical Wi-Fi link by not
         * chasing the resolution's full auto bitrate (~40Mbps at this panel size), which
         * saturates the link and stalls the stream. USB is unaffected.
         */
        const val LAN_MAX_BITRATE_KBPS = 20_000
    }
}
