package com.desklink.android.domain.repository

import android.view.Surface
import com.desklink.android.domain.model.DisplayConfig
import kotlinx.coroutines.flow.Flow

interface VideoStreamRepository {
    fun connect(config: DisplayConfig): Flow<VideoStreamEvent>
    suspend fun requestKeyframe()
    suspend fun disconnect()

    /**
     * Supplies (or clears with null) the render [Surface] from the SurfaceView.
     * Decoder configuration is deferred until both a Surface and a VIDEO_CONFIG
     * are available.
     */
    fun setSurface(surface: Surface?)

    /**
     * Registers a listener invoked once per render pass, with the server timestamp of the
     * newest frame released and the local `System.nanoTime` of that pass.
     *
     * Exposed so the composition root can feed the audio path its time reference; the
     * video layer stays unaware that audio exists. Pass null to clear.
     */
    fun setFrameRenderedListener(listener: ((serverTimestampUs: Long, localNanos: Long) -> Unit)?)

    /**
     * Drives one render tick: drains and renders all decoded frames ready this
     * vsync. Intended to be called from a Choreographer callback.
     * @return true if at least one frame was rendered.
     */

    fun renderFrame(): Boolean

    sealed interface VideoStreamEvent {
        data class ConfigReceived(val config: DisplayConfig) : VideoStreamEvent
        data object StreamStarted : VideoStreamEvent
        data object StreamStopped : VideoStreamEvent
        data class FrameAvailable(val timestampUs: Long) : VideoStreamEvent
        data class Error(val message: String) : VideoStreamEvent
    }
}
