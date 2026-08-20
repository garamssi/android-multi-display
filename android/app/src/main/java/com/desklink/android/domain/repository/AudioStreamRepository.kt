package com.desklink.android.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Audio channel: receives the Mac's system audio and plays it on this device.
 *
 * While this stream is running the Mac's own speakers are silent (the server holds a
 * muted Core Audio tap), so the stream ending is what gives the user their Mac audio
 * back. Nothing here is required for mirroring to work — video is independent.
 */
interface AudioStreamRepository {

    sealed interface AudioStreamEvent {
        /** The server announced the PCM format; playback can begin. */
        data class ConfigReceived(val sampleRate: Int, val channelCount: Int) : AudioStreamEvent
        /** The audio socket closed normally. */
        data object StreamStopped : AudioStreamEvent
        /** Audio failed. Mirroring continues; only sound is lost. */
        data class Error(val message: String) : AudioStreamEvent
    }

    /** Connects and plays until cancelled or the socket closes. */
    fun connect(): Flow<AudioStreamEvent>

    /**
     * Closes the audio socket and releases playback.
     *
     * Closing the SOCKET is what makes the Mac drop its tap and hand its own speakers
     * back. Merely stopping playback on this device leaves the server streaming into a
     * connection nobody reads: the receive window fills, the server's send blocks with no
     * error, the tap stays held, and BOTH machines end up silent until the process dies.
     * So this must run on every teardown path.
     */
    suspend fun disconnect()

    /**
     * Records that a video frame was rendered, so audio can be aligned to the picture.
     * Called from the render loop.
     */
    fun noteVideoRendered(serverTimestampUs: Long, localNanos: Long)
}
