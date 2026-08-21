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
            .combine(settingsRepository.playMacAudio) { state, playMacAudio ->
                state.copy(playMacAudio = playMacAudio, maxRefreshRate = settingsRepository.maxRefreshRate)
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
                    playMacAudio = settingsRepository.currentPlayMacAudio(),
                    maxRefreshRate = settingsRepository.maxRefreshRate,
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

    fun setPlayMacAudio(enabled: Boolean) = settingsRepository.setPlayMacAudio(enabled)

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
    val playMacAudio: Boolean = SettingsRepository.DEFAULT_PLAY_MAC_AUDIO,
    val maxRefreshRate: Int = 60,
) {
    val isNativeSelected: Boolean get() = width == nativeWidth && height == nativeHeight

    companion object {
        val AUDIO_OPTIONS = listOf(
            AudioOption(enabled = true, label = "Play here"),
            AudioOption(enabled = false, label = "Off"),
        )        // Presets are DERIVED from the panel, never listed. A fixed list only fits the
        // device it was written for: the previous one (2560x1600, 1920x1200, 1280x800) was
        // the 100/75/50% steps of one 16:10 tablet, so on a 16:9 panel those entries change
        // the aspect ratio and stretch the mirrored desktop, and on a smaller panel almost
        // all of them get filtered away.
        val RESOLUTION_SCALE_PERCENTS = listOf(75, 50)

        // Smallest dimension worth offering. Below this the stream is not useful as a
        // desktop and some encoders refuse the format outright.
        const val MIN_PRESET_DIMENSION = 320

        // Resolution options below the "Native" entry, scaled from the panel's own size so
        // the aspect ratio always matches. Dimensions are rounded DOWN to even numbers:
        // chroma-subsampled video formats reject odd dimensions, so an odd result would
        // fail to start the stream.
        fun resolutionPresets(nativeWidth: Int, nativeHeight: Int): List<Pair<Int, Int>> =
            RESOLUTION_SCALE_PERCENTS
                .map { percent ->
                    toEven(nativeWidth * percent / 100) to toEven(nativeHeight * percent / 100)
                }
                .filter { (width, height) ->
                    width >= MIN_PRESET_DIMENSION && height >= MIN_PRESET_DIMENSION &&
                        width < nativeWidth && height < nativeHeight
                }
                .distinct()

        private fun toEven(value: Int): Int = value - (value % 2)
        // Candidate frame rates. Offer only the ones the panel can present: a rate above
        // the refresh rate makes the Mac encode frames that are then dropped, costing
        // bitrate and CPU for nothing.
        val FPS_CANDIDATES = listOf(30, 60, 120)

        fun fpsOptions(maxRefreshRate: Int): List<Int> {
            val supported = FPS_CANDIDATES.filter { it <= maxRefreshRate }
            // Never present an empty control: an unexpectedly low reading still leaves the
            // lowest candidate usable.
            return supported.ifEmpty { listOf(FPS_CANDIDATES.min()) }
        }

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

data class AudioOption(val enabled: Boolean, val label: String)
