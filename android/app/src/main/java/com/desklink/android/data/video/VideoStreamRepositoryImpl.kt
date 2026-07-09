package com.desklink.android.data.video

import android.util.Log
import android.view.Surface
import com.desklink.android.data.codec.HEVCDecoder
import com.desklink.android.data.codec.VideoProtocol
import com.desklink.android.data.network.TCPClient
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.MessageType
import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.repository.VideoStreamRepository
import com.desklink.android.domain.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * A-H5: Video-channel repository.
 *
 * Connects a [TCPClient] to [ProtocolConstants.PORT_VIDEO], parses the initial
 * VIDEO_CONFIG (CodecID + uint16 length + Annex-B CSD) to configure the
 * [HEVCDecoder], then parses each VIDEO_FRAME (13-byte header -> PTS + keyframe
 * flag) and submits the NAL to the decoder. Emits [VideoStreamEvent]s describing
 * stream progress.
 *
 * The decoder needs a [Surface]; the presentation layer supplies it via
 * [setSurface] once the SurfaceView is ready. If a Surface arrives before the
 * config, configuration is deferred until both are present.
 */
class VideoStreamRepositoryImpl @Inject constructor(
    private val videoClient: TCPClient,
    private val decoder: HEVCDecoder,
    private val transport: Transport,
) : VideoStreamRepository {

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var pendingConfig: VideoProtocol.VideoConfig? = null

    @Volatile
    private var negotiated: DisplayConfig = DisplayConfig()

    @Volatile
    private var configured = false

    /** Supplies the render Surface (from the SurfaceView). */
    override fun setSurface(surface: Surface?) {
        // Just track the Surface: the render loop is gated on it (renderFrame) and the
        // decoder is (re)configured against the current Surface when the stream
        // (re)connects. Swapping a live codec's output Surface proved unreliable across
        // a long background (the codec ends up released/invalid), so the presentation
        // layer restarts the video stream on resume instead — which also gets a fresh
        // VIDEO_CONFIG + keyframe from the Mac.
        this.surface = surface
    }

    /**
     * Drains + renders all decoded frames ready this vsync. While there is no output
     * Surface (app backgrounded), skip it: releasing output buffers to a destroyed
     * Surface can push the codec into an error state and cause a black screen on
     * resume. Rendering resumes when [setSurface] supplies a live Surface again.
     */
    override fun renderFrame(): Boolean {
        if (surface == null) return false
        return decoder.renderFrame()
    }

    override fun connect(config: DisplayConfig): Flow<VideoStreamRepository.VideoStreamEvent> =
        callbackFlow {
            negotiated = config
            configured = false
            try {
                val host = transport.host()
                Log.i(TAG, "connecting video channel to $host:${ProtocolConstants.PORT_VIDEO}")
                videoClient.connect(host, ProtocolConstants.PORT_VIDEO)
                Log.i(TAG, "video channel connected; waiting for VIDEO_CONFIG. surface=${surface != null}")

                var frameCount = 0
                videoClient.receivePackets().collect { (type, payload) ->
                    when (type) {
                        MessageType.VIDEO_CONFIG -> {
                            Log.i(TAG, "VIDEO_CONFIG received (${payload.size} bytes)")
                            val cfg = VideoProtocol.parseConfig(payload)
                            if (cfg == null) {
                                trySend(
                                    VideoStreamRepository.VideoStreamEvent.Error(
                                        "Malformed VIDEO_CONFIG",
                                    ),
                                )
                                return@collect
                            }
                            pendingConfig = cfg
                            val effective = negotiated.copy(codec = cfg.codec)
                            negotiated = effective
                            trySend(
                                VideoStreamRepository.VideoStreamEvent.ConfigReceived(effective),
                            )
                            tryConfigureDecoder()
                            Log.i(TAG, "after VIDEO_CONFIG: configured=$configured (surface=${surface != null})")
                            if (configured) {
                                trySend(VideoStreamRepository.VideoStreamEvent.StreamStarted)
                            }
                        }

                        MessageType.VIDEO_FRAME -> {
                            val frame = VideoProtocol.parseFrame(payload) ?: return@collect
                            // Config may have arrived before the Surface; retry now.
                            if (!configured) tryConfigureDecoder()
                            if (frameCount < 3 || frameCount % 120 == 0) {
                                Log.i(TAG, "VIDEO_FRAME #$frameCount nal=${frame.nal.size} key=${frame.isKeyframe} configured=$configured")
                            }
                            frameCount++
                            if (configured) {
                                decoder.submitFrame(
                                    frame.nal,
                                    frame.timestampUs,
                                    frame.isKeyframe,
                                )
                                trySend(
                                    VideoStreamRepository.VideoStreamEvent.FrameAvailable(
                                        frame.timestampUs,
                                    ),
                                )
                            }
                        }

                        else -> { /* KEYFRAME_REQUEST etc. not received on client */ }
                    }
                }
                Log.i(TAG, "video stream stopped (socket closed)")
                trySend(VideoStreamRepository.VideoStreamEvent.StreamStopped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "video stream error", e)
                trySend(
                    VideoStreamRepository.VideoStreamEvent.Error(
                        e.message ?: "Video stream error",
                    ),
                )
            }

            awaitClose {
                // Collection cancelled: the socket/decoder are torn down by disconnect().
            }
        }

    /** Configures the decoder if both a Surface and a codec config are available. */
    private suspend fun tryConfigureDecoder() {
        if (configured) return
        val s = surface
        val cfg = pendingConfig
        if (s == null || cfg == null) {
            Log.i(TAG, "configure deferred: surface=${s != null} config=${cfg != null}")
            return
        }
        Log.i(TAG, "configuring decoder ${negotiated.width}x${negotiated.height} codec=${negotiated.codec} csd=${cfg.csd.size}")
        decoder.configure(s, negotiated, cfg.csd)
        configured = true
        Log.i(TAG, "decoder configured OK")
    }

    private companion object {
        const val TAG = "DeskLink"
    }

    override suspend fun requestKeyframe() {
        // KEYFRAME_REQUEST (0x12) has an empty payload.
        if (videoClient.isConnected) {
            videoClient.send(MessageType.KEYFRAME_REQUEST, ByteArray(0))
        }
    }

    override suspend fun disconnect() {
        configured = false
        pendingConfig = null
        decoder.release()
        videoClient.disconnect()
    }
}
