package com.desklink.android.data.input

import android.util.Log
import com.desklink.android.data.network.TCPClient
import com.desklink.android.domain.model.MessageType
import com.desklink.android.domain.model.PointerButtonEvent
import com.desklink.android.domain.model.ScrollEvent
import com.desklink.android.domain.model.TouchEvent
import com.desklink.android.domain.repository.InputRepository
import com.desklink.android.domain.transport.Transport
import java.io.IOException
import javax.inject.Inject

class InputRepositoryImpl @Inject constructor(
    private val inputClient: TCPClient,
    private val transport: Transport,
) : InputRepository {

    override suspend fun connect() {
        inputClient.connect(transport.host(), transport.inputPort())
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

    // Touch input is best-effort: dropping I/O/closed-socket failures here is deliberate, else the exception escapes the fire-and-forget send and crashes the app. Control-channel keep-alive drives reconnect.
    private suspend fun sendBestEffort(type: Byte, payload: () -> ByteArray) {
        if (!inputClient.isConnected) return
        try {
            inputClient.send(type, payload())
        } catch (e: IOException) {
            Log.i(TAG, "input send dropped (link down): ${e.message}")
        } catch (e: IllegalStateException) {
            // TCPClient.send throws this if a concurrent disconnect() nulled the stream after the isConnected check.
            Log.i(TAG, "input send dropped (socket closed): ${e.message}")
        }
    }

    private companion object {
        const val TAG = "DeskLink"
    }
}
