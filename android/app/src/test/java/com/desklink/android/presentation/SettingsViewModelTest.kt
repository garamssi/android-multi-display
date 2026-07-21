package com.desklink.android.presentation

import com.desklink.android.data.FakeSettingsStore
import com.desklink.android.data.device.ScreenMetricsProvider
import com.desklink.android.data.device.ScreenResolution
import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.data.settings.SettingsStore
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.TransportMode
import com.desklink.android.domain.transport.DiscoveredServer
import com.desklink.android.domain.transport.PeerDiscovery
import com.desklink.android.presentation.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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

    private fun repository(
        nativeWidth: Int = 2560,
        nativeHeight: Int = 1600,
        store: SettingsStore = FakeSettingsStore(),
    ) =
        SettingsRepository(
            object : ScreenMetricsProvider {
                override fun nativeResolution() = ScreenResolution(nativeWidth, nativeHeight)
            },
            store,
        )

    /** PeerDiscovery double emitting a fixed server list. */
    private class FakeDiscovery(
        private val flow: Flow<List<DiscoveredServer>> = flowOf(emptyList()),
    ) : PeerDiscovery {
        override fun servers(): Flow<List<DiscoveredServer>> = flow
    }

    private fun viewModel(
        nativeWidth: Int = 2560,
        nativeHeight: Int = 1600,
        discovery: PeerDiscovery = FakeDiscovery(),
    ): SettingsViewModel =
        SettingsViewModel(repository(nativeWidth, nativeHeight), discovery)

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

    @Test
    fun `touch input defaults to on`() {
        val vm = viewModel()
        assertTrue(vm.uiState.value.touchInputEnabled)
        assertTrue(SettingsRepository.DEFAULT_TOUCH_INPUT_ENABLED)
        assertTrue(repository().currentTouchInputEnabled())
    }

    @Test
    fun `setTouchInputEnabled toggles and persists across restart`() {
        val store = FakeSettingsStore()
        val repo = repository(store = store)

        repo.setTouchInputEnabled(false)
        assertFalse(repo.currentTouchInputEnabled())

        // New repository, same store == app restart: the choice is restored.
        assertFalse(repository(store = store).currentTouchInputEnabled())
    }

    @Test
    fun `transport defaults to USB with no manual host`() {
        val vm = viewModel()
        assertEquals(TransportMode.USB, vm.uiState.value.transportMode)
        assertEquals("", vm.uiState.value.manualHost)
        assertEquals(SettingsRepository.DEFAULT_TRANSPORT_MODE, repository().currentTransportMode())
    }

    @Test
    fun `view model transport setters delegate to the repository and trim the host`() {
        val repo = repository()
        val vm = SettingsViewModel(repo, FakeDiscovery())

        vm.setTransportMode(TransportMode.LAN)
        vm.setManualHost("  192.168.1.20  ")

        assertEquals(TransportMode.LAN, repo.currentTransportMode())
        assertEquals("192.168.1.20", repo.currentManualHost())
    }

    @Test
    fun `startDiscovery publishes discovered servers and selecting one sets the manual host`() = runTest {
        val repo = repository()
        val found = DiscoveredServer(name = "Garam's Mac", host = "192.168.0.5", port = 7100)
        val vm = SettingsViewModel(repo, FakeDiscovery(flowOf(listOf(found))))

        vm.startDiscovery()
        advanceUntilIdle()
        assertEquals(listOf(found), vm.discoveredServers.value)

        vm.selectDiscoveredServer(found)
        assertEquals("192.168.0.5", repo.currentManualHost())
    }

    @Test
    fun `settings persist across repository instances via the store`() {
        val store = FakeSettingsStore()
        val first = repository(store = store)
        first.setTransportMode(TransportMode.LAN)
        first.setManualHost("  192.168.0.5  ")
        first.setScrollSensitivity(5.0f)
        first.setNaturalScroll(false)
        first.setResolution(1920, 1200)
        first.setCodec(DisplayConfig.Codec.H264)

        // New repository, same store == app restart: persisted choices are restored.
        val restarted = repository(store = store)
        assertEquals(TransportMode.LAN, restarted.currentTransportMode())
        assertEquals("192.168.0.5", restarted.currentManualHost())
        assertEquals(5.0f, restarted.currentScrollSensitivity())
        assertFalse(restarted.currentNaturalScroll())
        assertEquals(1920, restarted.current().width)
        assertEquals(1200, restarted.current().height)
        assertEquals(DisplayConfig.Codec.H264, restarted.current().codec)
    }

    @Test
    fun `setPairingPin keeps only digits capped at the PIN length and persists`() {
        val store = FakeSettingsStore()
        val repo = repository(store = store)

        repo.setPairingPin("12ab34-56789")

        assertEquals("123456", repo.currentPairingPin()) // digits only, capped at 6
        // Survives a restart (new repository, same store).
        assertEquals("123456", repository(store = store).currentPairingPin())
    }

    @Test
    fun `stopDiscovery clears the discovered list`() = runTest {
        val vm = viewModel(discovery = FakeDiscovery(flowOf(listOf(
            DiscoveredServer("Mac", "10.0.0.2", 7100),
        ))))

        vm.startDiscovery()
        advanceUntilIdle()
        assertTrue(vm.discoveredServers.value.isNotEmpty())

        vm.stopDiscovery()
        assertTrue(vm.discoveredServers.value.isEmpty())
    }
}
