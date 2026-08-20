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

class KeepAliveController(
    private val scope: CoroutineScope,
    private val send: suspend (type: Byte, payload: ByteArray) -> Unit,
    private val onConnectionLost: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var lastPongAt: Long = clock()

    private var job: Job? = null

    fun timestampPayload(nowMs: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(nowMs).array()

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

    suspend fun onPacket(type: Byte, payload: ByteArray): Boolean {
        return when (type) {
            MessageType.PING -> {
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

    fun lastPongAt(): Long = lastPongAt

    fun stop() {
        job?.cancel()
        job = null
    }
}
