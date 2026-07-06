package com.desklink.android.data.input

import android.util.Log
import com.desklink.android.data.network.TCPClient
import com.desklink.android.domain.model.MessageType
import com.desklink.android.domain.model.PointerButtonEvent
import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.model.ScrollEvent
import com.desklink.android.domain.model.TouchEvent
import com.desklink.android.domain.repository.InputRepository
import java.io.IOException
import javax.inject.Inject

/**
 * A-H4: Input-channel repository. Connects a [TCPClient] to
 * [ProtocolConstants.PORT_INPUT] and sends serialized TOUCH_EVENT (0x20) /
 * TOUCH_BATCH (0x21) messages back to the Mac server.
 */
class InputRepositoryImpl @Inject constructor(
    private val inputClient: TCPClient,
) : InputRepository {

    override suspend fun connect() {
        inputClient.connect(ProtocolConstants.PORT_INPUT)
    }

    override suspend fun sendTouchEvent(event: TouchEvent) {
        sendBestEffort(MessageType.TOUCH_EVENT) { TouchSerializer.serialize(event) }
    }

    override suspend fun sendTouchBatch(events: List<TouchEvent>) {
        if (events.isEmpty()) return
        sendBestEffort(MessageType.TOUCH_BATCH) { TouchSerializer.serializeBatch(events) }
    }

    override suspend fun sendScroll(deltaX: Float, deltaY: Float) {
        sendBestEffort(MessageType.SCROLL) { ScrollSerializer.serialize(ScrollEvent(deltaX, deltaY)) }
    }

    override suspend fun sendPointerButton(event: PointerButtonEvent) {
        sendBestEffort(MessageType.POINTER_BUTTON) { PointerButtonSerializer.serialize(event) }
    }

    override suspend fun disconnect() {
        inputClient.disconnect()
    }

    /**
     * Touch input is best-effort. `isConnected` only reflects the LOCAL socket, so a
     * write can still fail with "broken pipe" (or the socket can be closed by teardown
     * between the check and the write) when the peer went away mid-gesture — during a
     * Mac stop, USB drop, or reconnect. That is expected, not fatal: drop the touch.
     * The control-channel keep-alive detects the real drop and drives reconnect.
     *
     * Without swallowing these I/O failures here, the exception escapes the
     * fire-and-forget `viewModelScope.launch` that sent the touch and crashes the app
     * (uncaught coroutine exception). This is a deliberate best-effort boundary, not a
     * blanket catch: only I/O/closed-socket failures are dropped, and control-channel
     * sends still surface their failures to trigger reconnect.
     */
    private suspend fun sendBestEffort(type: Byte, payload: () -> ByteArray) {
        if (!inputClient.isConnected) return
        try {
            inputClient.send(type, payload())
        } catch (e: IOException) {
            Log.i(TAG, "input send dropped (link down): ${e.message}")
        } catch (e: IllegalStateException) {
            // TCPClient.send throws this if the stream was nulled by a concurrent
            // disconnect() between the isConnected check and the write.
            Log.i(TAG, "input send dropped (socket closed): ${e.message}")
        }
    }

    private companion object {
        const val TAG = "DeskLink"
    }
}
