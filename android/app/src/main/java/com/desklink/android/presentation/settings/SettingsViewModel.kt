package com.desklink.android.presentation.settings

import androidx.lifecycle.ViewModel
import com.desklink.android.domain.model.DisplayConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setResolution(width: Int, height: Int) {
        _uiState.update { it.copy(width = width, height = height) }
    }

    fun setFps(fps: Int) {
        _uiState.update { it.copy(fps = fps) }
    }

    fun setBitrate(bitrateKbps: Int) {
        _uiState.update { it.copy(bitrateKbps = bitrateKbps) }
    }

    fun setCodec(codec: DisplayConfig.Codec) {
        _uiState.update { it.copy(codec = codec) }
    }

    fun toDisplayConfig(): DisplayConfig {
        val state = _uiState.value
        return DisplayConfig(
            width = state.width,
            height = state.height,
            fps = state.fps,
            codec = state.codec,
            bitrateKbps = state.bitrateKbps,
        )
    }
}

data class SettingsUiState(
    val width: Int = 2560,
    val height: Int = 1600,
    val fps: Int = 60,
    val bitrateKbps: Int = 20_000,
    val codec: DisplayConfig.Codec = DisplayConfig.Codec.HEVC,
) {
    companion object {
        val RESOLUTION_PRESETS = listOf(
            2560 to 1600,
            1920 to 1200,
            1920 to 1080,
            1280 to 800,
        )
        val FPS_OPTIONS = listOf(30, 60, 120)
        val BITRATE_OPTIONS = listOf(5_000, 10_000, 15_000, 20_000, 30_000, 40_000)
    }
}
