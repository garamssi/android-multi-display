package com.desklink.android.presentation

import com.desklink.android.presentation.settings.SettingsUiState
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Resolution presets are derived from the tablet's own panel, never a fixed list.
 *
 * A hardcoded list only fits the device it was written for. The previous one held
 * 2560x1600, 1920x1200 and 1280x800 — the 100/75/50% steps of one 16:10 tablet. On a 16:9
 * panel those entries change the aspect ratio, so the mirrored desktop is stretched; on a
 * smaller panel almost all of them are filtered away and the user is left with one choice.
 */
class ResolutionPresetTest {

    private fun presets(width: Int, height: Int) =
        SettingsUiState.resolutionPresets(nativeWidth = width, nativeHeight = height)

    private val panels = listOf(2560 to 1600, 1920 to 1080, 2000 to 1200, 1280 to 800)

    /** Every derived preset keeps the panel's aspect ratio. */
    @Test
    fun `presets preserve the native aspect ratio`() {
        for (panel in panels) {
            val nativeWidth = panel.first
            val nativeHeight = panel.second
            val nativeRatio = nativeWidth.toDouble() / nativeHeight
            for (preset in presets(nativeWidth, nativeHeight)) {
                val ratio = preset.first.toDouble() / preset.second
                assertTrue(
                    abs(ratio - nativeRatio) < 0.02,
                    "preset ${preset.first}x${preset.second} ratio $ratio vs native $nativeRatio",
                )
            }
        }
    }

    /** Presets are strictly smaller than native: native itself is a separate option. */
    @Test
    fun `presets are smaller than native`() {
        for (preset in presets(2560, 1600)) {
            assertTrue(preset.first < 2560 && preset.second < 1600, "preset $preset is not smaller")
        }
    }

    /**
     * Dimensions must be even: chroma-subsampled video formats reject odd dimensions, so
     * an odd result would fail to start the stream.
     */
    @Test
    fun `preset dimensions are even`() {
        for (panel in listOf(2560 to 1600, 1920 to 1080, 1333 to 999, 2001 to 1201)) {
            for (preset in presets(panel.first, panel.second)) {
                assertEquals(0, preset.first % 2, "odd width from $panel")
                assertEquals(0, preset.second % 2, "odd height from $panel")
            }
        }
    }

    /** A non-standard panel still gets usable presets rather than an empty list. */
    @Test
    fun `derives presets for a non standard panel`() {
        val derived = presets(2000, 1200)
        assertTrue(derived.isNotEmpty(), "no presets derived for 2000x1200")
        assertEquals(SettingsUiState.RESOLUTION_SCALE_PERCENTS.size, derived.size)
    }

    /** Scaling a small panel must not produce something an encoder cannot handle. */
    @Test
    fun `drops presets below the encoder minimum`() {
        for (preset in presets(480, 320)) {
            assertTrue(
                preset.first >= SettingsUiState.MIN_PRESET_DIMENSION &&
                    preset.second >= SettingsUiState.MIN_PRESET_DIMENSION,
                "preset $preset is below the encoder minimum",
            )
        }
    }

    @Test
    fun `no duplicate presets`() {
        val derived = presets(2560, 1600)
        assertEquals(derived.size, derived.distinct().size)
    }

    /** The scale steps are descending percentages strictly below native. */
    @Test
    fun `scale steps are descending percentages below native`() {
        val steps = SettingsUiState.RESOLUTION_SCALE_PERCENTS
        assertTrue(steps.isNotEmpty())
        assertEquals(steps.sortedDescending(), steps)
        assertTrue(steps.all { step -> step in 1..99 }, "100% would duplicate the Native option")
    }
}
