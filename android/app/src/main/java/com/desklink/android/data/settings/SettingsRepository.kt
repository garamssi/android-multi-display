package com.desklink.android.data.settings

import com.desklink.android.data.device.ScreenMetricsProvider
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.TransportMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holder for the user-selected [DisplayConfig] and connection/input preferences.
 *
 * Shared (as a Hilt @Singleton) between the Settings screen (which mutates it) and the
 * Connection screen (which reads it), so a selection survives navigation. Values are
 * PERSISTED via [SettingsStore] (SharedPreferences), so they also survive app restarts:
 * the observable flows are seeded from the store at construction and every setter writes
 * back. The store is synchronous, so the eager seeding is race-free.
 *
 * The effective default is derived from the device's *real native* screen size (via
 * [ScreenMetricsProvider]) rather than a hardcoded 1920x1200, so out of the box the app
 * requests the panel's full resolution. Persisted values override the native default when
 * present; the native size (`nativeWidth`/`nativeHeight`) is always taken from the current
 * device so a saved config still advertises the right panel size after a device change.
 */
@Singleton
class SettingsRepository @Inject constructor(
    screenMetrics: ScreenMetricsProvider,
    private val store: SettingsStore,
) {
    /**
     * The device's native resolution as a fully-formed default config (landscape,
     * native bitrate). Exposed so the Settings UI can offer a "Native" preset and so
     * every edited config keeps the same `nativeWidth`/`nativeHeight`.
     */
    val nativeConfig: DisplayConfig =
        screenMetrics.nativeResolution().let {
            DisplayConfig.forNativeResolution(it.width, it.height)
        }

    val nativeWidth: Int get() = nativeConfig.width
    val nativeHeight: Int get() = nativeConfig.height

    // Seeded from the store (falling back to the native-derived default), keeping the
    // current device's native size via copy().
    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<DisplayConfig> = _config.asStateFlow()

    /**
     * Scroll sensitivity multiplier applied to the normalized two-finger scroll delta
     * BEFORE it is sent to the Mac (so the Mac injects 1:1). A local input preference,
     * not part of the negotiated video [DisplayConfig] / wire protocol.
     */
    private val _scrollSensitivity =
        MutableStateFlow(store.getFloat(KEY_SCROLL_SENSITIVITY, DEFAULT_SCROLL_SENSITIVITY))
    val scrollSensitivity: StateFlow<Float> = _scrollSensitivity.asStateFlow()

    /**
     * Scroll direction preference, applied on the tablet before sending (like
     * [scrollSensitivity]) so the Mac and wire protocol are unchanged. `true` = natural
     * (content follows the fingers — the macOS default and the current behavior);
     * `false` inverts the delta for traditional/reversed scrolling.
     */
    private val _naturalScroll =
        MutableStateFlow(store.getBoolean(KEY_NATURAL_SCROLL, DEFAULT_NATURAL_SCROLL))
    val naturalScroll: StateFlow<Boolean> = _naturalScroll.asStateFlow()

    /**
     * How the client reaches the Mac ([TransportMode.USB] by default). Read by the
     * transport layer at connect time, so changing it in Settings then reconnecting
     * switches the dial target. LAN is plaintext/dev-only in this phase (see
     * [TransportMode]).
     */
    private val _transportMode = MutableStateFlow(loadTransportMode())
    val transportMode: StateFlow<TransportMode> = _transportMode.asStateFlow()

    /**
     * The Mac's IP (or hostname) to dial in [TransportMode.LAN], entered manually or
     * chosen from discovery. Ignored in USB mode. Blank until set; a blank host in LAN
     * mode surfaces as a connection error, not a silent fallback.
     */
    private val _manualHost = MutableStateFlow(store.getString(KEY_MANUAL_HOST, ""))
    val manualHost: StateFlow<String> = _manualHost.asStateFlow()

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

    fun setTransportMode(mode: TransportMode) {
        _transportMode.update { mode }
        store.putString(KEY_TRANSPORT_MODE, mode.name)
    }

    /** Stores the manual LAN host, trimmed (surrounding whitespace is never a valid host). */
    fun setManualHost(value: String) {
        val trimmed = value.trim()
        _manualHost.update { trimmed }
        store.putString(KEY_MANUAL_HOST, trimmed)
    }

    fun current(): DisplayConfig = _config.value

    fun currentScrollSensitivity(): Float = _scrollSensitivity.value

    fun currentNaturalScroll(): Boolean = _naturalScroll.value

    fun currentTransportMode(): TransportMode = _transportMode.value

    fun currentManualHost(): String = _manualHost.value

    // --- Seeding from the store ---

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

    companion object {
        const val MIN_SCROLL_SENSITIVITY = 1.0f
        const val MAX_SCROLL_SENSITIVITY = 6.0f
        /** Matches the previous fixed Mac-side gain, so default feel is unchanged. */
        const val DEFAULT_SCROLL_SENSITIVITY = 3.0f
        /** Natural scrolling on by default (macOS default, current behavior). */
        const val DEFAULT_NATURAL_SCROLL = true
        /** USB is the default and only secure transport today; LAN is opt-in. */
        val DEFAULT_TRANSPORT_MODE = TransportMode.USB

        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_FPS = "fps"
        private const val KEY_BITRATE = "bitrateKbps"
        private const val KEY_CODEC = "codec"
        private const val KEY_SCROLL_SENSITIVITY = "scrollSensitivity"
        private const val KEY_NATURAL_SCROLL = "naturalScroll"
        private const val KEY_TRANSPORT_MODE = "transportMode"
        private const val KEY_MANUAL_HOST = "manualHost"
    }
}
