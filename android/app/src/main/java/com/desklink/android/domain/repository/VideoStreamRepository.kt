package com.desklink.android.domain.repository

import android.view.Surface
import com.desklink.android.domain.model.DisplayConfig
import kotlinx.coroutines.flow.Flow

interface VideoStreamRepository {
    fun connect(config: DisplayConfig): Flow<VideoStreamEvent>
    suspend fun requestKeyframe()
    suspend fun disconnect()

    fun setSurface(surface: Surface?)

    // Feeds the audio path its time reference (newest frame per render pass) without the
    // video layer knowing audio exists. Null clears it.
    fun setFrameRenderedListener(listener: ((serverTimestampUs: Long, localNanos: Long) -> Unit)?)

    fun renderFrame(frameTimeNanos: Long, vsyncPeriodNanos: Long): Boolean

    sealed interface VideoStreamEvent {
        data class ConfigReceived(val config: DisplayConfig) : VideoStreamEvent
        data object StreamStarted : VideoStreamEvent
        data object StreamStopped : VideoStreamEvent
        data class FrameAvailable(val timestampUs: Long) : VideoStreamEvent

        /**
         * The picture's real size, read from the decoded bitstream.
         *
         * Not the negotiated resolution: that is what the Mac was asked for, and in mirror
         * mode it captures its own screen's size instead. The view needs the real one to
         * avoid stretching the picture to the panel's shape.
         */
        data class SizeChanged(val width: Int, val height: Int) : VideoStreamEvent
        data class Error(val message: String) : VideoStreamEvent
    }
}
