package com.desklink.android.data.settings

import com.desklink.android.data.device.ScreenMetricsProvider
import com.desklink.android.domain.model.DisplayConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory, process-lifetime holder for the user-selected [DisplayConfig].
 *
 * Shared (as a Hilt @Singleton) between the Settings screen (which mutates it) and
 * the Connection screen (which reads it to drive [DisplayConfig] into the connect
 * flow), so a selection made in Settings survives navigation back to Connection.
 *
 * The effective default is derived from the device's *real native* screen size (via
 * [ScreenMetricsProvider]) rather than a hardcoded 1920x1200, so out of the box the
 * app requests the panel's full resolution and picks a bitrate to match — fixing the
 * blur caused by the Mac rendering low and the tablet upscaling. The native size is
 * also carried on every config (`nativeWidth`/`nativeHeight`) and preserved across
 * user edits so the handshake can always advertise it.
 */
@Singleton
class SettingsRepository @Inject constructor(
    screenMetrics: ScreenMetricsProvider,
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

    private val _config = MutableStateFlow(nativeConfig)
    val config: StateFlow<DisplayConfig> = _config.asStateFlow()

    /**
     * Scroll sensitivity multiplier applied to the normalized two-finger scroll delta
     * BEFORE it is sent to the Mac (so the Mac injects 1:1). A local input preference,
     * not part of the negotiated video [DisplayConfig] / wire protocol.
     */
    private val _scrollSensitivity = MutableStateFlow(DEFAULT_SCROLL_SENSITIVITY)
    val scrollSensitivity: StateFlow<Float> = _scrollSensitivity.asStateFlow()

    /**
     * Scroll direction preference, applied on the tablet before sending (like
     * [scrollSensitivity]) so the Mac and wire protocol are unchanged. `true` = natural
     * (content follows the fingers — the macOS default and the current behavior);
     * `false` inverts the delta for traditional/reversed scrolling.
     */
    private val _naturalScroll = MutableStateFlow(DEFAULT_NATURAL_SCROLL)
    val naturalScroll: StateFlow<Boolean> = _naturalScroll.asStateFlow()

    fun setResolution(width: Int, height: Int) =
        _config.update { it.copy(width = width, height = height) }

    fun setFps(fps: Int) = _config.update { it.copy(fps = fps) }

    fun setBitrate(bitrateKbps: Int) = _config.update { it.copy(bitrateKbps = bitrateKbps) }

    fun setCodec(codec: DisplayConfig.Codec) = _config.update { it.copy(codec = codec) }

    fun setScrollSensitivity(value: Float) =
        _scrollSensitivity.update { value.coerceIn(MIN_SCROLL_SENSITIVITY, MAX_SCROLL_SENSITIVITY) }

    fun setNaturalScroll(enabled: Boolean) = _naturalScroll.update { enabled }

    fun current(): DisplayConfig = _config.value

    fun currentScrollSensitivity(): Float = _scrollSensitivity.value

    fun currentNaturalScroll(): Boolean = _naturalScroll.value

    companion object {
        const val MIN_SCROLL_SENSITIVITY = 1.0f
        const val MAX_SCROLL_SENSITIVITY = 6.0f
        /** Matches the previous fixed Mac-side gain, so default feel is unchanged. */
        const val DEFAULT_SCROLL_SENSITIVITY = 3.0f
        /** Natural scrolling on by default (macOS default, current behavior). */
        const val DEFAULT_NATURAL_SCROLL = true
    }
}
