package com.desklink.android.presentation

import com.desklink.android.data.FakeSettingsStore
import com.desklink.android.data.device.ScreenMetricsProvider
import com.desklink.android.data.device.ScreenResolution
import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.domain.model.TouchEvent
import com.desklink.android.domain.repository.ConnectionRepository
import com.desklink.android.domain.repository.InputRepository
import com.desklink.android.domain.repository.VideoStreamRepository
import com.desklink.android.domain.usecase.SendTouchUseCase
import com.desklink.android.presentation.display.DisplayViewModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies the Settings "Touch input" toggle actually gates every pointer/scroll
 * sender in [DisplayViewModel]: when off, nothing reaches [SendTouchUseCase]; when on,
 * the events are forwarded (the pre-existing behavior).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DisplayViewModelTouchGateTest {

    private val dispatcher = StandardTestDispatcher()

    private fun settings(store: FakeSettingsStore = FakeSettingsStore()) =
        SettingsRepository(
            object : ScreenMetricsProvider {
                override fun nativeResolution() = ScreenResolution(2560, 1600)
            },
            store,
        )

    private fun viewModel(
        settings: SettingsRepository,
        sendTouch: SendTouchUseCase,
    ): DisplayViewModel {
        val connection = mockk<ConnectionRepository>(relaxed = true)
        // init { observeReconnectsToRestartVideo() } collects this; a steady Disconnected
        // flow keeps the collector idle without triggering a video (re)start.
        every { connection.connectionState } returns
            MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        return DisplayViewModel(
            videoStream = mockk<VideoStreamRepository>(relaxed = true),
            inputRepository = mockk<InputRepository>(relaxed = true),
            connectionRepository = connection,
            sendTouchUseCase = sendTouch,
            settingsRepository = settings,
        )
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
    fun `touch off drops every pointer and scroll sender`() = runTest {
        val settings = settings().apply { setTouchInputEnabled(false) }
        val sendTouch = mockk<SendTouchUseCase>(relaxed = true)
        val vm = viewModel(settings, sendTouch)

        vm.sendPointerDown(0.5f, 0.5f)
        vm.sendPointerMove(0.5f, 0.5f)
        vm.sendPointerUp(0.5f, 0.5f)
        vm.sendScroll(1f, 1f)
        vm.sendLongPressRightClick(0.5f, 0.5f)
        vm.cancelTouch(0.5f, 0.5f)
        advanceUntilIdle()

        coVerify(exactly = 0) { sendTouch.send(any()) }
        coVerify(exactly = 0) { sendTouch.sendScroll(any(), any()) }
        coVerify(exactly = 0) { sendTouch.sendRightClick(any(), any()) }
    }

    @Test
    fun `touch on forwards pointer and scroll events`() = runTest {
        val settings = settings() // default: enabled
        val sendTouch = mockk<SendTouchUseCase>(relaxed = true)
        val vm = viewModel(settings, sendTouch)

        vm.sendPointerDown(0.5f, 0.5f)
        vm.sendScroll(1f, 1f)
        advanceUntilIdle()

        coVerify(exactly = 1) { sendTouch.send(match { it.action == TouchEvent.Action.DOWN }) }
        coVerify(exactly = 1) { sendTouch.sendScroll(any(), any()) }
    }
}
