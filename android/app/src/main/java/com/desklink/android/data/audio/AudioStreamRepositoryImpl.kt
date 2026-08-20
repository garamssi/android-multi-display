package com.desklink.android.data.audio

import android.util.Log
import com.desklink.android.data.network.TCPClient
import com.desklink.android.domain.model.MessageType
import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.repository.AudioStreamRepository
import com.desklink.android.domain.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Audio-channel repository.
 *
 * Connects a [TCPClient] to [ProtocolConstants.PORT_AUDIO], reads AUDIO_CONFIG to learn
 * the PCM layout, then plays each AUDIO_FRAME through an [AudioTrackPlayer] that steers
 * the audio onto the video clock.
 *
 * AUDIO_FRAMEs arriving before AUDIO_CONFIG are dropped rather than guessed at: the
 * sample rate determines both playback speed and every sync calculation, so playing PCM
 * at an assumed rate would be audibly wrong and would corrupt the timing reference.
 */
class AudioStreamRepositoryImpl @Inject constructor(
    private val audioClient: TCPClient,
    private val transport: Transport,
) : AudioStreamRepository {

    private companion object {
        const val TAG = "AudioStream"
    }

    /**
     * Single thread that owns socket reads and every `AudioTrack` write.
     *
     * `AudioTrack.write(WRITE_BLOCKING)` blocks until the track has room — up to a buffer
     * period per chunk, and far longer when a backlog arrives at once. `callbackFlow`'s
     * body and the `collect` lambda run in the COLLECTOR's context, so without moving them
     * this would block `viewModelScope` (Main), starving the Choreographer callback that
     * drives rendering: audio would stutter the mirror and freeze the sync reference,
     * which is exactly what audio must never do.
     *
     * Single-threaded rather than `Dispatchers.IO` so writes and teardown cannot overlap;
     * `AudioTrack` is released on this same thread, which is what keeps `stop()` from
     * racing an in-flight `write`.
     *
     * A plain executor rather than `newSingleThreadContext`, which is a delicate
     * opt-in API. The thread lives for the process: this is an app-lifetime singleton, and
     * tearing the thread down between sessions would only add a way for a late write to
     * land on a dead dispatcher.
     */
    private val audioDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "desklink-audio") }
            .asCoroutineDispatcher()

    /**
     * Player for the CURRENT collection.
     *
     * Written only by the collecting coroutine and read by the render thread via
     * [noteVideoRendered], so it is volatile. A collection that is being torn down clears
     * it only if it still owns it, so a cancelled job cannot stop the player belonging to
     * the reconnect that replaced it — which would silently strand the new session with no
     * playback and no way to recover, since AUDIO_CONFIG has already gone by.
     */
    @Volatile
    private var player: AudioTrackPlayer? = null

    override fun noteVideoRendered(serverTimestampUs: Long, localNanos: Long) {
        player?.noteVideoRendered(serverTimestampUs, localNanos)
    }

    override suspend fun disconnect() {
        audioClient.disconnect()
    }

    override fun connect(): Flow<AudioStreamRepository.AudioStreamEvent> = callbackFlow {
        var framesBeforeConfig = 0
        var ownPlayer: AudioTrackPlayer? = null
        try {
            val host = transport.host()
            val port = transport.audioPort()
            Log.i(TAG, "connecting audio channel to $host:$port")
            audioClient.connect(host, port)

            audioClient.receivePackets().collect { (type, payload) ->
                when (type) {
                    MessageType.AUDIO_CONFIG -> {
                        val format = AudioProtocol.parseConfig(payload)
                        if (format == null) {
                            trySend(AudioStreamRepository.AudioStreamEvent.Error("Malformed AUDIO_CONFIG"))
                            return@collect
                        }
                        // A reconnect re-announces the format; replace the old track so a
                        // changed sample rate cannot be played at the previous one.
                        ownPlayer?.stop()
                        val started = AudioTrackPlayer(format)
                        if (!started.start()) {
                            // Report it instead of leaving a player that silently drops
                            // every chunk while the UI believes audio is running.
                            trySend(
                                AudioStreamRepository.AudioStreamEvent.Error(
                                    "Device cannot play ${format.sampleRate} Hz, ${format.channelCount} ch",
                                ),
                            )
                            return@collect
                        }
                        ownPlayer = started
                        player = started
                        Log.i(TAG, "AUDIO_CONFIG: ${format.sampleRate} Hz, ${format.channelCount} ch")
                        trySend(
                            AudioStreamRepository.AudioStreamEvent.ConfigReceived(
                                sampleRate = format.sampleRate,
                                channelCount = format.channelCount,
                            ),
                        )
                    }

                    MessageType.AUDIO_FRAME -> {
                        val active = ownPlayer
                        if (active == null) {
                            framesBeforeConfig++
                            if (framesBeforeConfig == 1) {
                                Log.e(TAG, "AUDIO_FRAME before AUDIO_CONFIG; dropping until the format is known")
                            }
                            return@collect
                        }
                        val chunk = AudioProtocol.parseFrame(payload) ?: return@collect
                        active.play(chunk)
                    }

                    else -> { /* no other message types are received on this channel */ }
                }
            }
            Log.i(TAG, "audio stream stopped (socket closed)")
            trySend(AudioStreamRepository.AudioStreamEvent.StreamStopped)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "audio stream error: $e")
            trySend(AudioStreamRepository.AudioStreamEvent.Error(e.message ?: "Audio stream failed"))
        }

        awaitClose {
            ownPlayer?.stop()
            // Only clear the shared reference if this collection still owns it, so a
            // reconnect that already installed its own player is left alone.
            if (player === ownPlayer) player = null
            // Closing the socket is what releases the Mac's tap. Without it the server
            // keeps sending into a connection nobody reads and both machines go silent.
            // awaitClose runs on a cancelled coroutine, so the close is dispatched
            // separately rather than awaited here.
            closeSocket()
            Log.i(TAG, "audio channel closed")
        }
    }.flowOn(audioDispatcher)

    /**
     * Closes the socket outside the cancelled collection.
     *
     * [awaitClose] cannot suspend, and by the time it runs the collecting coroutine is
     * already cancelled, so the close cannot be awaited there.
     */
    private fun closeSocket() {
        CoroutineScope(audioDispatcher + SupervisorJob()).launch {
            runCatching { audioClient.disconnect() }
                .onFailure { Log.e(TAG, "error closing audio socket: $it") }
        }
    }
}
