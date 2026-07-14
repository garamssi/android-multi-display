package com.desklink.android.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.DisplayRotation
import com.desklink.android.domain.model.TransportMode
import com.desklink.android.domain.transport.DiscoveredServer
import com.desklink.android.domain.transport.PeerDiscovery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val peerDiscovery: PeerDiscovery,
) : ViewModel() {

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

    private var discoveryJob: Job? = null

    val uiState: StateFlow<SettingsUiState> =
        combine(
            settingsRepository.config,
            settingsRepository.scrollSensitivity,
            settingsRepository.naturalScroll,
            settingsRepository.transportMode,
            settingsRepository.manualHost,
        ) { config, sensitivity, naturalScroll, transportMode, manualHost ->
            config.toUiState(sensitivity, naturalScroll, transportMode, manualHost)
        }
            .combine(settingsRepository.touchInputEnabled) { state, touchInputEnabled ->
                state.copy(touchInputEnabled = touchInputEnabled)
            }
            .combine(settingsRepository.displayRotation) { state, rotation ->
                state.copy(rotation = rotation)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = settingsRepository.current().toUiState(
                    settingsRepository.currentScrollSensitivity(),
                    settingsRepository.currentNaturalScroll(),
                    settingsRepository.currentTransportMode(),
                    settingsRepository.currentManualHost(),
                ).copy(
                    touchInputEnabled = settingsRepository.currentTouchInputEnabled(),
                    rotation = settingsRepository.currentDisplayRotation(),
                ),
            )

    fun setResolution(width: Int, height: Int) = settingsRepository.setResolution(width, height)

    fun useNativeResolution() =
        settingsRepository.setResolution(settingsRepository.nativeWidth, settingsRepository.nativeHeight)

    fun setFps(fps: Int) = settingsRepository.setFps(fps)

    fun setBitrate(bitrateKbps: Int) = settingsRepository.setBitrate(bitrateKbps)

    fun setCodec(codec: DisplayConfig.Codec) = settingsRepository.setCodec(codec)

    fun setScrollSensitivity(value: Float) = settingsRepository.setScrollSensitivity(value)

    fun setNaturalScroll(enabled: Boolean) = settingsRepository.setNaturalScroll(enabled)

    fun setTouchInputEnabled(enabled: Boolean) = settingsRepository.setTouchInputEnabled(enabled)

    fun setDisplayRotation(rotation: DisplayRotation) = settingsRepository.setDisplayRotation(rotation)

    fun setTransportMode(mode: TransportMode) = settingsRepository.setTransportMode(mode)

    fun setManualHost(value: String) = settingsRepository.setManualHost(value)

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

    fun selectDiscoveredServer(server: DiscoveredServer) =
        settingsRepository.setManualHost(server.host)

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }

    fun toDisplayConfig(): DisplayConfig = settingsRepository.current()

    private fun DisplayConfig.toUiState(
        scrollSensitivity: Float,
        naturalScroll: Boolean,
        transportMode: TransportMode,
        manualHost: String,
    ) = SettingsUiState(
        width = width,
        height = height,
        fps = fps,
        bitrateKbps = bitrateKbps,
        codec = codec,
        nativeWidth = nativeWidth,
        nativeHeight = nativeHeight,
        scrollSensitivity = scrollSensitivity,
        naturalScroll = naturalScroll,
        transportMode = transportMode,
        manualHost = manualHost,
    )
}

data class SettingsUiState(
    val width: Int = DisplayConfig().width,
    val height: Int = DisplayConfig().height,
    val fps: Int = DisplayConfig().fps,
    val bitrateKbps: Int = DisplayConfig().bitrateKbps,
    val codec: DisplayConfig.Codec = DisplayConfig().codec,
    val nativeWidth: Int = DisplayConfig().nativeWidth,
    val nativeHeight: Int = DisplayConfig().nativeHeight,
    val scrollSensitivity: Float = 3.0f,
    val naturalScroll: Boolean = true,
    val transportMode: TransportMode = TransportMode.USB,
    val manualHost: String = "",
    val touchInputEnabled: Boolean = true,
    val rotation: DisplayRotation = DisplayRotation.ROTATION_0,
) {
    val isNativeSelected: Boolean get() = width == nativeWidth && height == nativeHeight

    companion object {
        val RESOLUTION_PRESETS = listOf(
            2560 to 1600,
            1920 to 1200,
            1280 to 800,
        )
        val FPS_OPTIONS = listOf(30, 60, 120)

        val BITRATE_OPTIONS = listOf(
            BitrateOption(10_000, "Low"),
            BitrateOption(20_000, "Medium"),
            BitrateOption(40_000, "High"),
        )

        val SCROLL_SPEED_OPTIONS = listOf(
            ScrollSpeedOption(1.5f, "Slow"),
            ScrollSpeedOption(3.0f, "Normal"),
            ScrollSpeedOption(5.0f, "Fast"),
        )

        val SCROLL_DIRECTION_OPTIONS = listOf(
            ScrollDirectionOption(natural = true, label = "Natural"),
            ScrollDirectionOption(natural = false, label = "Reversed"),
        )

        val TOUCH_INPUT_OPTIONS = listOf(
            TouchInputOption(enabled = true, label = "On"),
            TouchInputOption(enabled = false, label = "Off"),
        )

        val ROTATION_OPTIONS = listOf(
            DisplayRotation.ROTATION_0,
            DisplayRotation.ROTATION_90,
            DisplayRotation.ROTATION_180,
            DisplayRotation.ROTATION_270,
        )
    }
}

data class BitrateOption(val kbps: Int, val label: String)

data class ScrollSpeedOption(val sensitivity: Float, val label: String)

data class ScrollDirectionOption(val natural: Boolean, val label: String)

data class TouchInputOption(val enabled: Boolean, val label: String)
