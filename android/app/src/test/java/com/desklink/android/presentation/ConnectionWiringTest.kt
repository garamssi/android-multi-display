package com.desklink.android.presentation

import app.cash.turbine.test
import com.desklink.android.data.device.ScreenMetricsProvider
import com.desklink.android.data.device.ScreenResolution
import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.repository.UsbStateMonitor
import com.desklink.android.domain.usecase.ConnectToServerUseCase
import com.desklink.android.presentation.connection.ConnectionViewModel
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.desklink.android.domain.model.ConnectionState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A-L4 wiring test: the connect flow reads the user-selected DisplayConfig from
 * SettingsRepository (mutated via the settings screen) and passes exactly that
 * config to ConnectToServerUseCase.connect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionWiringTest {

    private val dispatcher = StandardTestDispatcher()

    /** A SettingsRepository whose "native" screen size is fixed for deterministic tests. */
    private fun repo(nativeWidth: Int = 2560, nativeHeight: Int = 1600) =
        SettingsRepository(
            object : ScreenMetricsProvider {
                override fun nativeResolution() = ScreenResolution(nativeWidth, nativeHeight)
            },
        )

    /** A UsbStateMonitor emitting a fixed connectivity value. */
    private fun usbMonitor(connected: Boolean = false) = object : UsbStateMonitor {
        override fun usbConnected(): Flow<Boolean> = flowOf(connected)
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `default config is derived from the device native resolution`() {
        val repo = repo(nativeWidth = 2560, nativeHeight = 1600)
        val default = repo.current()
        // Native 2560x1600 -> requested resolution defaults to native, 40 Mbps, HEVC.
        assertEquals(2560, default.width)
        assertEquals(1600, default.height)
        assertEquals(2560, default.nativeWidth)
        assertEquals(1600, default.nativeHeight)
        assertEquals(40_000, default.bitrateKbps)
        assertEquals(DisplayConfig.Codec.HEVC, default.codec)
        assertEquals(60, default.fps)
    }

    @Test
    fun `portrait native metrics are normalised to landscape`() {
        val repo = repo(nativeWidth = 1600, nativeHeight = 2560)
        assertEquals(2560, repo.current().width)
        assertEquals(1600, repo.current().height)
        assertEquals(2560, repo.nativeWidth)
        assertEquals(1600, repo.nativeHeight)
    }

    @Test
    fun `connect passes the user-selected settings config with native size preserved`() =
        runTest(dispatcher) {
            val repo = repo(nativeWidth = 2560, nativeHeight = 1600)
            // User picks a smaller streaming resolution + explicit options in Settings.
            repo.setResolution(1280, 800)
            repo.setFps(120)
            repo.setBitrate(40_000)
            repo.setCodec(DisplayConfig.Codec.H264)

            val useCase = mockk<ConnectToServerUseCase>()
            every { useCase.connectionState } returns
                MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
            coEvery { useCase.connect(any()) } returns Unit

            val vm = ConnectionViewModel(useCase, repo, usbMonitor())
            vm.connect()
            advanceUntilIdle()

            val expected = DisplayConfig(
                width = 1280,
                height = 800,
                fps = 120,
                codec = DisplayConfig.Codec.H264,
                bitrateKbps = 40_000,
                // Native size is preserved regardless of the chosen streaming resolution.
                nativeWidth = 2560,
                nativeHeight = 1600,
            )
            coVerify(exactly = 1) { useCase.connect(expected) }
        }

    @Test
    fun `usbConnected reflects the monitor state`() = runTest(dispatcher) {
        val useCase = mockk<ConnectToServerUseCase>()
        every { useCase.connectionState } returns
            MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

        val vm = ConnectionViewModel(useCase, repo(), usbMonitor(connected = true))

        vm.usbConnected.test {
            assertEquals(false, awaitItem()) // stateIn initial value
            assertEquals(true, awaitItem()) // value observed from the monitor
            cancelAndIgnoreRemainingEvents()
        }
    }
}
