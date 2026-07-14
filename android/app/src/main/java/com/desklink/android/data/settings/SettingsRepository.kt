package com.desklink.android.data.settings

import com.desklink.android.data.device.ScreenMetricsProvider
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.DisplayRotation
import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.model.TransportMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    screenMetrics: ScreenMetricsProvider,
    private val store: SettingsStore,
) {
    val nativeConfig: DisplayConfig =
        screenMetrics.nativeResolution().let {
            DisplayConfig.forNativeResolution(it.width, it.height)
        }

    val nativeWidth: Int get() = nativeConfig.width
    val nativeHeight: Int get() = nativeConfig.height

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<DisplayConfig> = _config.asStateFlow()

    private val _scrollSensitivity =
        MutableStateFlow(store.getFloat(KEY_SCROLL_SENSITIVITY, DEFAULT_SCROLL_SENSITIVITY))
    val scrollSensitivity: StateFlow<Float> = _scrollSensitivity.asStateFlow()

    private val _naturalScroll =
        MutableStateFlow(store.getBoolean(KEY_NATURAL_SCROLL, DEFAULT_NATURAL_SCROLL))
    val naturalScroll: StateFlow<Boolean> = _naturalScroll.asStateFlow()

    private val _touchInputEnabled =
        MutableStateFlow(store.getBoolean(KEY_TOUCH_INPUT_ENABLED, DEFAULT_TOUCH_INPUT_ENABLED))
    val touchInputEnabled: StateFlow<Boolean> = _touchInputEnabled.asStateFlow()

    private val _displayRotation = MutableStateFlow(loadDisplayRotation())
    val displayRotation: StateFlow<DisplayRotation> = _displayRotation.asStateFlow()

    private val _transportMode = MutableStateFlow(loadTransportMode())
    val transportMode: StateFlow<TransportMode> = _transportMode.asStateFlow()

    private val _manualHost = MutableStateFlow(store.getString(KEY_MANUAL_HOST, ""))
    val manualHost: StateFlow<String> = _manualHost.asStateFlow()

    private val _pairingPin = MutableStateFlow(store.getString(KEY_PAIRING_PIN, ""))
    val pairingPin: StateFlow<String> = _pairingPin.asStateFlow()

    private val _lastConnectedHost = MutableStateFlow(store.getString(KEY_LAST_HOST, ""))
    val lastConnectedHost: StateFlow<String> = _lastConnectedHost.asStateFlow()

    fun setResolution(width: Int, height: Int) {
        _config.update { it.copy(width = width, height = height) }
        store.putInt(KEY_WIDTH, width)
        store.putInt(KEY_HEIGHT, height)
    }

    fun setFps(fps: Int) {
        _config.update { it.copy(fps = fps) }
        store.putInt(KEY_FPS, fps)
    }

    fun setBitrate(bitrateKbps: Int) {
        _config.update { it.copy(bitrateKbps = bitrateKbps) }
        store.putInt(KEY_BITRATE, bitrateKbps)
    }

    fun setCodec(codec: DisplayConfig.Codec) {
        _config.update { it.copy(codec = codec) }
        store.putString(KEY_CODEC, codec.name)
    }

    fun setScrollSensitivity(value: Float) {
        val clamped = value.coerceIn(MIN_SCROLL_SENSITIVITY, MAX_SCROLL_SENSITIVITY)
        _scrollSensitivity.update { clamped }
        store.putFloat(KEY_SCROLL_SENSITIVITY, clamped)
    }

    fun setNaturalScroll(enabled: Boolean) {
        _naturalScroll.update { enabled }
        store.putBoolean(KEY_NATURAL_SCROLL, enabled)
    }

    fun setTouchInputEnabled(enabled: Boolean) {
        _touchInputEnabled.update { enabled }
        store.putBoolean(KEY_TOUCH_INPUT_ENABLED, enabled)
    }

    fun setDisplayRotation(rotation: DisplayRotation) {
        _displayRotation.update { rotation }
        store.putInt(KEY_DISPLAY_ROTATION, rotation.degrees)
    }

    fun setTransportMode(mode: TransportMode) {
        _transportMode.update { mode }
        store.putString(KEY_TRANSPORT_MODE, mode.name)
    }

    fun setManualHost(value: String) {
        val trimmed = value.trim()
        _manualHost.update { trimmed }
        store.putString(KEY_MANUAL_HOST, trimmed)
    }

    fun setPairingPin(value: String) {
        val digits = value.filter { it.isDigit() }.take(ProtocolConstants.PAIRING_PIN_LENGTH)
        _pairingPin.update { digits }
        store.putString(KEY_PAIRING_PIN, digits)
    }

    fun current(): DisplayConfig = _config.value

    fun currentScrollSensitivity(): Float = _scrollSensitivity.value

    fun currentNaturalScroll(): Boolean = _naturalScroll.value

    fun currentTouchInputEnabled(): Boolean = _touchInputEnabled.value

    fun currentDisplayRotation(): DisplayRotation = _displayRotation.value

    fun currentTransportMode(): TransportMode = _transportMode.value

    fun currentManualHost(): String = _manualHost.value

    fun currentPairingPin(): String = _pairingPin.value

    fun setLastConnectedHost(host: String) {
        val trimmed = host.trim()
        _lastConnectedHost.update { trimmed }
        store.putString(KEY_LAST_HOST, trimmed)
    }

    fun currentLastConnectedHost(): String = _lastConnectedHost.value

    private fun loadConfig(): DisplayConfig = nativeConfig.copy(
        width = store.getInt(KEY_WIDTH, nativeConfig.width),
        height = store.getInt(KEY_HEIGHT, nativeConfig.height),
        fps = store.getInt(KEY_FPS, nativeConfig.fps),
        bitrateKbps = store.getInt(KEY_BITRATE, nativeConfig.bitrateKbps),
        codec = runCatching { DisplayConfig.Codec.valueOf(store.getString(KEY_CODEC, nativeConfig.codec.name)) }
            .getOrDefault(nativeConfig.codec),
    )

    private fun loadTransportMode(): TransportMode =
        runCatching { TransportMode.valueOf(store.getString(KEY_TRANSPORT_MODE, DEFAULT_TRANSPORT_MODE.name)) }
            .getOrDefault(DEFAULT_TRANSPORT_MODE)

    private fun loadDisplayRotation(): DisplayRotation =
        DisplayRotation.fromDegrees(store.getInt(KEY_DISPLAY_ROTATION, DEFAULT_DISPLAY_ROTATION.degrees))

    companion object {
        const val MIN_SCROLL_SENSITIVITY = 1.0f
        const val MAX_SCROLL_SENSITIVITY = 6.0f
        const val DEFAULT_SCROLL_SENSITIVITY = 3.0f
        const val DEFAULT_NATURAL_SCROLL = true
        const val DEFAULT_TOUCH_INPUT_ENABLED = true
        val DEFAULT_DISPLAY_ROTATION = DisplayRotation.ROTATION_0
        val DEFAULT_TRANSPORT_MODE = TransportMode.USB


        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_FPS = "fps"
        private const val KEY_BITRATE = "bitrateKbps"
        private const val KEY_CODEC = "codec"
        private const val KEY_SCROLL_SENSITIVITY = "scrollSensitivity"
        private const val KEY_NATURAL_SCROLL = "naturalScroll"
        private const val KEY_TOUCH_INPUT_ENABLED = "touchInputEnabled"
        private const val KEY_DISPLAY_ROTATION = "displayRotationDegrees"
        private const val KEY_TRANSPORT_MODE = "transportMode"
        private const val KEY_MANUAL_HOST = "manualHost"
        private const val KEY_PAIRING_PIN = "pairingPin"
        private const val KEY_LAST_HOST = "lastConnectedHost"
    }
}
