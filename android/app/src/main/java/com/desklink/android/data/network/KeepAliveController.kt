package com.desklink.android.data.network

import com.desklink.android.domain.model.MessageType
import com.desklink.android.domain.model.ProtocolConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Drives the Control-channel keep-alive (A-H2):
 *  - sends PING (0x07) every [ProtocolConstants.PING_INTERVAL] ms;
 *  - replies PONG (0x08) to any inbound PING;
 *  - records the last time a PONG was received;
 *  - if `now - lastPong > `[ProtocolConstants.PING_TIMEOUT]` it declares the
 *    connection lost via [onConnectionLost].
 *
 * PING/PONG payload is an int64 (Big-Endian) millisecond Unix timestamp.
 *
 * A [clock] seam (defaults to [System.currentTimeMillis]) makes timeout detection
 * testable under a virtual clock; the PING cadence uses [delay] so it advances
 * with a test scheduler's virtual time.
 */
class KeepAliveController(
    private val scope: CoroutineScope,
    private val send: suspend (type: Byte, payload: ByteArray) -> Unit,
    private val onConnectionLost: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var lastPongAt: Long = clock()

    private var job: Job? = null

    /** Serializes a PING/PONG payload: int64 BE milliseconds. */
    fun timestampPayload(nowMs: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(nowMs).array()

    /**
     * Starts the periodic PING loop and timeout watchdog. Idempotent-ish: a second
     * start after [stop] restarts cleanly.
     */
    fun start() {
        stop()
        lastPongAt = clock()
        job = scope.launch {
            while (isActive) {
                delay(ProtocolConstants.PING_INTERVAL)
                val now = clock()
                if (now - lastPongAt > ProtocolConstants.PING_TIMEOUT) {
                    onConnectionLost()
                    return@launch
                }
                try {
                    send(MessageType.PING, timestampPayload(now))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A send failure means the socket is gone; treat as lost.
                    onConnectionLost()
                    return@launch
                }
            }
        }
    }

    /**
     * Feeds a received control packet. Returns true if the packet was a keep-alive
     * message (and was handled here), false otherwise so the caller can process it.
     */
    suspend fun onPacket(type: Byte, payload: ByteArray): Boolean {
        return when (type) {
            MessageType.PING -> {
                // Echo the sender's timestamp back as PONG.
                send(MessageType.PONG, payload)
                true
            }

            MessageType.PONG -> {
                lastPongAt = clock()
                true
            }

            else -> false
        }
    }

    /** Last recorded PONG time (ms), for diagnostics/tests. */
    fun lastPongAt(): Long = lastPongAt

    fun stop() {
        job?.cancel()
        job = null
    }
}
