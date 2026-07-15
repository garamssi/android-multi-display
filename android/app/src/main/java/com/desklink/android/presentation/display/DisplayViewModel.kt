package com.desklink.android.presentation.display

import android.util.Log
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.ConnectionState
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

/**
 * Orchestrates the DisplayScreen: wires the render [Surface] into the video stream
 * repository, starts the video stream + input channel, forwards touches, and tears
 * everything down on exit (A-H4/A-H5 presentation glue).
 */
@HiltViewModel
class DisplayViewModel @Inject constructor(
    private val videoStream: VideoStreamRepository,
    private val inputRepository: InputRepository,
    private val connectionRepository: ConnectionRepository,
    private val sendTouchUseCase: SendTouchUseCase,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /**
     * The shared control-channel state. The DisplayScreen observes this to show a
     * reconnecting overlay and to leave the mirror (back to Connect) once the link
     * is terminally lost — so a Mac-side stop or a pulled USB cable no longer leaves
     * a frozen last frame on screen.
     */
    val connectionState: StateFlow<ConnectionState> = connectionRepository.connectionState

    private var videoJob: Job? = null
    private var started = false

    init {
        observeReconnectsToRestartVideo()
    }

    /**
     * The control channel can reconnect on its own (e.g. after the app returns from
     * the background and the keep-alive had lapsed). When it does, the Mac recreates
     * the video and input servers, so our existing video/input sockets are dead and
     * the mirror is frozen/black. Watch for a return to Connected AFTER a drop and
     * rebuild the video + input pipeline against the current Surface.
     */
    private fun observeReconnectsToRestartVideo() {
        viewModelScope.launch {
            var wasConnected = false
            connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        // Only a *re*-connect needs a restart; the initial connect is
                        // driven by onSurfaceAvailable().
                        if (wasConnected && started) restartVideoPipeline()
                        wasConnected = true
                    }

                    is ConnectionState.Disconnected, is ConnectionState.Error ->
                        wasConnected = false

                    else -> {
                        // Transient (Connecting/Handshaking/Negotiating/Reconnecting):
                        // keep wasConnected so the following Connected is seen as a
                        // reconnect.
                    }
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

    /** Called from SurfaceHolder.Callback.surfaceCreated. */
    fun onSurfaceAvailable(surface: Surface) {
        Log.i(TAG, "onSurfaceAvailable (started=$started)")
        videoStream.setSurface(surface)
        if (!started) {
            started = true
            startStreaming()
        }
    }

    /** Called from SurfaceHolder.Callback.surfaceDestroyed. */
    fun onSurfaceDestroyed() {
        // Background: stop feeding/rendering the decoder whose output Surface is gone,
        // and force a fresh video (re)start on return. A long background otherwise
        // leaves the MediaCodec in a released/invalid state that can't be recovered by
        // swapping the surface -> black screen on resume. onSurfaceAvailable() then
        // reconnects the video stream, so the Mac re-sends VIDEO_CONFIG + a keyframe and
        // the decoder is rebuilt cleanly against the new Surface.
        Log.i(TAG, "onSurfaceDestroyed -> stopping video until resume")
        videoStream.setSurface(null)
        videoJob?.cancel()
        videoJob = null
        started = false
    }

    /** Drives one render tick from the Choreographer callback. */
    fun renderFrame(): Boolean = videoStream.renderFrame()

    private fun startStreaming() {
        val config = settingsRepository.current()
        Log.i(TAG, "startStreaming config=${config.width}x${config.height} codec=${config.codec}")
        videoJob = viewModelScope.launch {
            // Input channel connects alongside the video stream.
            runCatching { inputRepository.connect() }
                .onFailure { Log.e(TAG, "input channel connect failed", it) }
            videoStream.connect(config).collect { event ->
                Log.i(TAG, "video event: $event")
            }
        }
    }

    /** Forwards a two-finger scroll to the server, scaled by the user's scroll
     *  sensitivity and direction preference (applied here so the Mac injects 1:1).
     *  Reversed flips the delta sign; the same path covers live scroll and fling. */
    fun sendScroll(deltaX: Float, deltaY: Float) {
        val sensitivity = settingsRepository.currentScrollSensitivity()
        val direction = if (settingsRepository.currentNaturalScroll()) 1f else -1f
        val scale = sensitivity * direction
        viewModelScope.launch {
            sendTouchUseCase.sendScroll(deltaX * scale, deltaY * scale)
        }
    }

    /**
     * A long-press became a right-click: release the in-flight left press (the DOWN was
     * already forwarded) and then inject the right-click. Both run in ORDER on one
     * coroutine so the wire order is deterministic — a separate launch per step could
     * let the right-click overtake the left release.
     */
    fun sendLongPressRightClick(x: Float, y: Float) {
        viewModelScope.launch {
            sendTouchUseCase.send(cancelTouchEvent(x, y))
            sendTouchUseCase.sendRightClick(x, y)
        }
    }

    /**
     * Releases an in-flight single touch on the Mac. Called when a second finger
     * lands and the gesture becomes an app-owned multi-touch: the first finger's
     * DOWN was already forwarded, so without this the Mac would keep the button
     * pressed. [x]/[y] are the last known normalized position of the primary pointer.
     */
    fun cancelTouch(x: Float, y: Float) {
        viewModelScope.launch {
            sendTouchUseCase.send(cancelTouchEvent(x, y))
        }
    }

    /** Saved floating-controls-handle position (fraction 0..1 of the draggable area). */
    fun controlHandleFractionX(): Float = settingsRepository.currentControlHandleX()

    fun controlHandleFractionY(): Float = settingsRepository.currentControlHandleY()

    /** Persists the handle position so it reappears in the same spot next launch. */
    fun saveControlHandleFraction(x: Float, y: Float) =
        settingsRepository.setControlHandlePosition(x, y)

    /** Presses the primary pointer down at a normalized position (mouse-down on the Mac). */
    fun sendPointerDown(x: Float, y: Float) = sendPrimary(TouchEvent.Action.DOWN, x, y)

    /** Moves the primary pointer. Before a DOWN it reads as a hover; after, as a drag —
     *  the Mac classifies it by whether the pointer is currently held. */
    fun sendPointerMove(x: Float, y: Float) = sendPrimary(TouchEvent.Action.MOVE, x, y)

    /** Releases the primary pointer (mouse-up on the Mac). */
    fun sendPointerUp(x: Float, y: Float) = sendPrimary(TouchEvent.Action.UP, x, y)

    private fun sendPrimary(action: TouchEvent.Action, x: Float, y: Float) {
        viewModelScope.launch { sendTouchUseCase.send(primaryTouch(action, x, y)) }
    }

    /** A single primary-pointer (id 0) touch of [action] at a normalized position. */
    private fun primaryTouch(action: TouchEvent.Action, x: Float, y: Float) = TouchEvent(
        action = action,
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
        pressure = 0.toUShort(),
        pointerId = 0.toUByte(),
        timestampUs = System.nanoTime() / 1000L,
    )

    /** A CANCEL touch for the primary pointer at a normalized position (releases an
     *  in-flight left press on the Mac). */
    private fun cancelTouchEvent(x: Float, y: Float) = primaryTouch(TouchEvent.Action.CANCEL, x, y)

    /** Full teardown: stop video stream, close input, disconnect control, release decoder. */
    fun teardown() {
        videoJob?.cancel()
        videoJob = null
        started = false
        viewModelScope.launch {
            // Run the actual teardown in NonCancellable. teardown() is called from the
            // explicit exit paths (Back / Disconnect / Settings / terminal-error nav),
            // all on the main thread while viewModelScope is still active; the launch
            // therefore begins eagerly (Main.immediate) and enters NonCancellable BEFORE
            // the following navigation clears the ViewModel and cancels the scope. That
            // guarantees connectionRepository.disconnect() runs on those paths, so the
            // reconnect loop + keep-alive don't leak on the singleton scope (a zombie
            // connection with no UI).
            //
            // Not covered here: a clear that happens WITHOUT a prior explicit teardown
            // (e.g. the OS destroys the Activity while backgrounded). In that case
            // onCleared() -> teardown() runs on an already-cancelled scope and this
            // launch is a no-op. Fixing that fully requires the connection lifecycle to
            // be owned by a scope that outlives the screen (roadmap P4, session state
            // machine) rather than the ViewModel; tracked there to avoid a stale
            // teardown racing a new session on the shared singleton repositories.
            withContext(NonCancellable) {
                runCatching { videoStream.disconnect() }
                runCatching { inputRepository.disconnect() }
                // Also close the CONTROL channel so connectionState becomes Disconnected;
                // otherwise the Connection screen still sees "Connected" and immediately
                // bounces back to Display (black screen). This also stops auto-reconnect.
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
