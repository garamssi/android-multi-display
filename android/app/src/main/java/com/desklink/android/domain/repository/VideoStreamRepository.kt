package com.desklink.android.domain.repository

import android.view.Surface
import com.desklink.android.domain.model.DisplayConfig
import kotlinx.coroutines.flow.Flow

interface VideoStreamRepository {
    fun connect(config: DisplayConfig): Flow<VideoStreamEvent>
    suspend fun requestKeyframe()
    suspend fun disconnect()

    sealed interface VideoStreamEvent {
        data class ConfigReceived(val config: DisplayConfig) : VideoStreamEvent
        data object StreamStarted : VideoStreamEvent
        data object StreamStopped : VideoStreamEvent
        data class FrameAvailable(val timestampUs: Long) : VideoStreamEvent
        data class Error(val message: String) : VideoStreamEvent
    }
}
