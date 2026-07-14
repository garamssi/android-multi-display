package com.desklink.android.presentation.display

import android.util.Log
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.domain.model.DisplayRotation
import com.desklink.android.domain.model.TouchEvent
import com.desklink.android.domain.repository.ConnectionRepository
import com.desklink.android.domain.repository.InputRepository
import com.desklink.android.domain.repository.VideoStreamRepository
import com.desklink.android.domain.usecase.SendTouchUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DisplayViewModel @Inject constructor(
    private val videoStream: VideoStreamRepository,
    private val inputRepository: InputRepository,
    private val connectionRepository: ConnectionRepository,
    private val sendTouchUseCase: SendTouchUseCase,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionRepository.connectionState

    private var videoJob: Job? = null
    private var started = false

    init {
        observeReconnectsToRestartVideo()
    }

    private fun observeReconnectsToRestartVideo() {
        viewModelScope.launch {
            var wasConnected = false
            connectionState.collect { state ->
                when {
                    state.isConnected -> {
                        // Only a re-connect needs a restart; the initial connect is driven by onSurfaceAvailable().
                        if (wasConnected && started) restartVideoPipeline()
                        wasConnected = true
                    }

                    // Terminal (idle/error): session is over, so a following Connected is a fresh connect, not a reconnect.
                    state.isTerminal -> wasConnected = false

                    else -> {}
                }
            }
        }
    }

    private fun restartVideoPipeline() {
        Log.i(TAG, "reconnect detected -> restarting video/input pipeline")
        videoJob?.cancel()
        viewModelScope.launch {
            runCatching { videoStream.disconnect() }
            runCatching { inputRepository.disconnect() }
            startStreaming()
        }
    }

    fun onSurfaceAvailable(surface: Surface) {
        Log.i(TAG, "onSurfaceAvailable (started=$started)")
        videoStream.setSurface(surface)
        if (!started) {
            started = true
            startStreaming()
        }
    }

    fun onSurfaceDestroyed() {
        // MediaCodec can't recover after a long background with its output Surface gone (black screen), so stop video and force a fresh restart on resume.
        Log.i(TAG, "onSurfaceDestroyed -> stopping video until resume")
        videoStream.setSurface(null)
        videoJob?.cancel()
        videoJob = null
        started = false
    }

    fun renderFrame(): Boolean = videoStream.renderFrame()

    fun displayRotation(): DisplayRotation = settingsRepository.currentDisplayRotation()

    private fun startStreaming() {
        // Orient for the rotation so the negotiated config, decoder MediaFormat, and virtual display all agree.
        val config = settingsRepository.current().oriented(settingsRepository.currentDisplayRotation())
        Log.i(TAG, "startStreaming config=${config.width}x${config.height} codec=${config.codec}")
        videoJob = viewModelScope.launch {
            runCatching { inputRepository.connect() }
                .onFailure { Log.e(TAG, "input channel connect failed", it) }
            videoStream.connect(config).collect { event ->
                Log.i(TAG, "video event: $event")
            }
        }
    }

    fun sendScroll(deltaX: Float, deltaY: Float) {
        if (!touchInputEnabled()) return
        val sensitivity = settingsRepository.currentScrollSensitivity()
        val direction = if (settingsRepository.currentNaturalScroll()) 1f else -1f
        // A 180 flip inverts both screen axes, so the scroll delta must be negated to match the flipped view/touch coords.
        val flip = if (settingsRepository.currentDisplayRotation().isFlipped) -1f else 1f
        val scale = sensitivity * direction * flip
        viewModelScope.launch {
            sendTouchUseCase.sendScroll(deltaX * scale, deltaY * scale)
        }
    }

    // Release the in-flight left press then inject the right-click, in ORDER on one coroutine so the right-click can't overtake the release.
    fun sendLongPressRightClick(x: Float, y: Float) {
        if (!touchInputEnabled()) return
        viewModelScope.launch {
            sendTouchUseCase.send(cancelTouchEvent(x, y))
            sendTouchUseCase.sendRightClick(x, y)
        }
    }

    fun cancelTouch(x: Float, y: Float) {
        if (!touchInputEnabled()) return
        viewModelScope.launch {
            sendTouchUseCase.send(cancelTouchEvent(x, y))
        }
    }

    fun sendPointerDown(x: Float, y: Float) = sendPrimary(TouchEvent.Action.DOWN, x, y)

    fun sendPointerMove(x: Float, y: Float) = sendPrimary(TouchEvent.Action.MOVE, x, y)

    fun sendPointerUp(x: Float, y: Float) = sendPrimary(TouchEvent.Action.UP, x, y)

    private fun sendPrimary(action: TouchEvent.Action, x: Float, y: Float) {
        if (!touchInputEnabled()) return
        viewModelScope.launch { sendTouchUseCase.send(primaryTouch(action, x, y)) }
    }

    private fun touchInputEnabled(): Boolean = settingsRepository.currentTouchInputEnabled()

    private fun primaryTouch(action: TouchEvent.Action, x: Float, y: Float) = TouchEvent(
        action = action,
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
        pressure = 0.toUShort(),
        pointerId = 0.toUByte(),
        timestampUs = System.nanoTime() / 1000L,
    )

    private fun cancelTouchEvent(x: Float, y: Float) = primaryTouch(TouchEvent.Action.CANCEL, x, y)

    fun teardown() {
        videoJob?.cancel()
        videoJob = null
        started = false
        viewModelScope.launch {
            // NonCancellable so control disconnect still runs after navigation cancels the scope; otherwise the reconnect loop leaks as a zombie connection.
            withContext(NonCancellable) {
                runCatching { videoStream.disconnect() }
                runCatching { inputRepository.disconnect() }
                // Close the CONTROL channel too, else the Connection screen still sees "Connected" and bounces back (black screen); also stops auto-reconnect.
                runCatching { connectionRepository.disconnect() }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        teardown()
    }

    private companion object {
        const val TAG = "DeskLink"
    }
}
