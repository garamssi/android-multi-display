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
            val stored = settingsRepository.current()
            // Wi-Fi (LAN) rides a shared, often-2.4GHz link that can't sustain the
            // USB-class bitrate/fps, so a high setting just buffers and stutters. Cap the
            // frame rate and bitrate for LAN; resolution stays native (tablet-derived) so
            // the picture stays sharp. USB is unaffected.
            val config = if (settingsRepository.currentTransportMode() == TransportMode.LAN) {
                stored.copy(
                    fps = stored.fps.coerceAtMost(LAN_MAX_FPS),
                    bitrateKbps = stored.bitrateKbps.coerceAtMost(LAN_MAX_BITRATE_KBPS),
                )
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
        /** Wi-Fi frame-rate cap — smoother than 60fps on a shared/2.4GHz link. */
        const val LAN_MAX_FPS = 30

        /** Wi-Fi bitrate cap (kbps) — fits a typical 2.4GHz link without buffering. */
        const val LAN_MAX_BITRATE_KBPS = 12_000
    }
}
