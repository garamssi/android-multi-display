package com.desklink.android.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.TransportMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Settings screen ViewModel. Reads/writes the shared [SettingsRepository] so a
 * selection persists across navigation and can be consumed by the connect flow
 * (A-L4). The UI state's initial defaults come from the repository's native-derived
 * config, keeping one consistent default resolution/bitrate across the app.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> =
        combine(
            settingsRepository.config,
            settingsRepository.scrollSensitivity,
            settingsRepository.naturalScroll,
            settingsRepository.transportMode,
            settingsRepository.manualHost,
        ) { config, sensitivity, naturalScroll, transportMode, manualHost ->
            config.toUiState(sensitivity, naturalScroll, transportMode, manualHost)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = settingsRepository.current().toUiState(
                settingsRepository.currentScrollSensitivity(),
                settingsRepository.currentNaturalScroll(),
                settingsRepository.currentTransportMode(),
                settingsRepository.currentManualHost(),
            ),
        )

    fun setResolution(width: Int, height: Int) = settingsRepository.setResolution(width, height)

    /** Reset the streaming resolution to the device's real native size. */
    fun useNativeResolution() =
        settingsRepository.setResolution(settingsRepository.nativeWidth, settingsRepository.nativeHeight)

    fun setFps(fps: Int) = settingsRepository.setFps(fps)

    fun setBitrate(bitrateKbps: Int) = settingsRepository.setBitrate(bitrateKbps)

    fun setCodec(codec: DisplayConfig.Codec) = settingsRepository.setCodec(codec)

    fun setScrollSensitivity(value: Float) = settingsRepository.setScrollSensitivity(value)

    fun setNaturalScroll(enabled: Boolean) = settingsRepository.setNaturalScroll(enabled)

    fun setTransportMode(mode: TransportMode) = settingsRepository.setTransportMode(mode)

    fun setManualHost(value: String) = settingsRepository.setManualHost(value)

    /** The current user selection as a [DisplayConfig] (used by the connect flow). */
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
) {
    /** True when the current streaming resolution equals the device's native size. */
    val isNativeSelected: Boolean get() = width == nativeWidth && height == nativeHeight

    companion object {
        /** Fixed resolution presets shown below the dynamic "Native" option. */
        val RESOLUTION_PRESETS = listOf(
            2560 to 1600,
            1920 to 1200,
            1280 to 800,
        )
        val FPS_OPTIONS = listOf(30, 60, 120)

        /** Bitrate presets: Low / Medium / High (10 / 20 / 40 Mbps). */
        val BITRATE_OPTIONS = listOf(
            BitrateOption(10_000, "Low"),
            BitrateOption(20_000, "Medium"),
            BitrateOption(40_000, "High"),
        )

        /** Scroll speed presets (sensitivity multiplier applied on the tablet). */
        val SCROLL_SPEED_OPTIONS = listOf(
            ScrollSpeedOption(1.5f, "Slow"),
            ScrollSpeedOption(3.0f, "Normal"),
            ScrollSpeedOption(5.0f, "Fast"),
        )

        /** Scroll direction options. Natural = content follows the fingers (macOS
         *  default); Reversed inverts it. Applied on the tablet before sending. */
        val SCROLL_DIRECTION_OPTIONS = listOf(
            ScrollDirectionOption(natural = true, label = "Natural"),
            ScrollDirectionOption(natural = false, label = "Reversed"),
        )
    }
}

/** A selectable bitrate preset with a human label. */
data class BitrateOption(val kbps: Int, val label: String)

/** A selectable scroll-speed preset (sensitivity multiplier) with a human label. */
data class ScrollSpeedOption(val sensitivity: Float, val label: String)

/** A selectable scroll-direction preset with a human label. */
data class ScrollDirectionOption(val natural: Boolean, val label: String)
