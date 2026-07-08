package com.desklink.android.presentation

import com.desklink.android.data.device.ScreenMetricsProvider
import com.desklink.android.data.device.ScreenResolution
import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.presentation.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for the Settings option -> DisplayConfig mapping and for the
 * native-resolution detection wired through an injected [ScreenMetricsProvider].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private fun repository(nativeWidth: Int = 2560, nativeHeight: Int = 1600) =
        SettingsRepository(
            object : ScreenMetricsProvider {
                override fun nativeResolution() = ScreenResolution(nativeWidth, nativeHeight)
            },
        )

    private fun viewModel(nativeWidth: Int = 2560, nativeHeight: Int = 1600): SettingsViewModel =
        SettingsViewModel(repository(nativeWidth, nativeHeight))

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial ui state reflects the detected native resolution`() {
        val vm = viewModel(nativeWidth = 2560, nativeHeight = 1600)
        val state = vm.uiState.value
        assertEquals(2560, state.width)
        assertEquals(1600, state.height)
        assertEquals(2560, state.nativeWidth)
        assertEquals(1600, state.nativeHeight)
        assertEquals(40_000, state.bitrateKbps)
        assertEquals(DisplayConfig.Codec.HEVC, state.codec)
        assertTrue(state.isNativeSelected)
    }

    @Test
    fun `option selections map into the DisplayConfig used by connect`() {
        val vm = viewModel(nativeWidth = 2560, nativeHeight = 1600)

        vm.setResolution(1920, 1200)
        vm.setFps(30)
        vm.setBitrate(10_000)
        vm.setCodec(DisplayConfig.Codec.H264)

        val config = vm.toDisplayConfig()
        assertEquals(1920, config.width)
        assertEquals(1200, config.height)
        assertEquals(30, config.fps)
        assertEquals(10_000, config.bitrateKbps)
        assertEquals(DisplayConfig.Codec.H264, config.codec)
        // Native size is preserved even after choosing a smaller streaming resolution.
        assertEquals(2560, config.nativeWidth)
        assertEquals(1600, config.nativeHeight)
        assertFalse(config.width == config.nativeWidth && config.height == config.nativeHeight)
    }

    @Test
    fun `useNativeResolution restores the native streaming size`() {
        val vm = viewModel(nativeWidth = 2560, nativeHeight = 1600)
        vm.setResolution(1280, 800)
        assertEquals(1280, vm.toDisplayConfig().width)

        vm.useNativeResolution()

        val config = vm.toDisplayConfig()
        assertEquals(2560, config.width)
        assertEquals(1600, config.height)
    }

    @Test
    fun `initial scroll sensitivity is the default`() {
        val vm = viewModel()
        assertEquals(
            SettingsRepository.DEFAULT_SCROLL_SENSITIVITY,
            vm.uiState.value.scrollSensitivity,
        )
    }

    @Test
    fun `setScrollSensitivity clamps to the allowed range`() {
        val repo = repository()

        repo.setScrollSensitivity(SettingsRepository.MAX_SCROLL_SENSITIVITY + 5f)
        assertEquals(SettingsRepository.MAX_SCROLL_SENSITIVITY, repo.currentScrollSensitivity())

        repo.setScrollSensitivity(SettingsRepository.MIN_SCROLL_SENSITIVITY - 5f)
        assertEquals(SettingsRepository.MIN_SCROLL_SENSITIVITY, repo.currentScrollSensitivity())

        repo.setScrollSensitivity(4.0f)
        assertEquals(4.0f, repo.currentScrollSensitivity())
    }

    @Test
    fun `scroll direction defaults to natural`() {
        val vm = viewModel()
        assertTrue(vm.uiState.value.naturalScroll)
        assertEquals(SettingsRepository.DEFAULT_NATURAL_SCROLL, repository().currentNaturalScroll())
    }

    @Test
    fun `setNaturalScroll toggles the preference`() {
        val repo = repository()

        repo.setNaturalScroll(false)
        assertFalse(repo.currentNaturalScroll())

        repo.setNaturalScroll(true)
        assertTrue(repo.currentNaturalScroll())
    }
}
