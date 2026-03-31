package com.desklink.android.data.network

import com.desklink.android.domain.model.ProtocolConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

/**
 * TCP client for connecting to Mac server via ADB-forwarded localhost ports.
 */
class TCPClient @Inject constructor() {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    suspend fun connect(port: Int) = withContext(Dispatchers.IO) {
        disconnect()
        val newSocket = Socket()
        newSocket.tcpNoDelay = true
        newSocket.sendBufferSize = ProtocolConstants.SOCKET_BUFFER_SIZE
        newSocket.receiveBufferSize = ProtocolConstants.SOCKET_BUFFER_SIZE
        newSocket.soTimeout = 0 // blocking reads
        newSocket.connect(
            InetSocketAddress("127.0.0.1", port),
            ProtocolConstants.HANDSHAKE_TIMEOUT.toInt()
        )
        socket = newSocket
        outputStream = newSocket.getOutputStream()
    }

    suspend fun send(type: Byte, payload: ByteArray) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: throw IllegalStateException("Not connected")
        val packet = PacketFramer.frame(type, payload)
        stream.write(packet)
        stream.flush()
    }

    /**
     * Returns a Flow that emits received packets as (type, payload) pairs.
     * Uses ByteArrayOutputStream to avoid O(n²) array concatenation.
     */
    fun receivePackets(): Flow<Pair<Byte, ByteArray>> = flow {
        val input = socket?.getInputStream() ?: throw IllegalStateException("Not connected")
        val readBuffer = ByteArray(ProtocolConstants.SOCKET_BUFFER_SIZE)
        val accumulator = ByteArrayOutputStream(ProtocolConstants.SOCKET_BUFFER_SIZE)

        while (currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive != false) {
            val bytesRead = input.read(readBuffer)
            if (bytesRead == -1) break

            accumulator.write(readBuffer, 0, bytesRead)

            val data = accumulator.toByteArray()
            var offset = 0

            while (offset < data.size) {
                when (val result = PacketFramer.unframe(data, offset, data.size - offset)) {
                    is PacketFramer.UnframeResult.Success -> {
                        emit(Pair(result.type, result.payload))
                        offset += result.consumed
                    }
                    is PacketFramer.UnframeResult.NeedMoreData -> break
                    is PacketFramer.UnframeResult.Error -> {
                        throw RuntimeException("Packet error: ${result.message}")
                    }
                }
            }

            // Keep only unconsumed data
            accumulator.reset()
            if (offset < data.size) {
                accumulator.write(data, offset, data.size - offset)
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) {}
        outputStream = null
        socket = null
    }
}

private suspend fun currentCoroutineContext() = kotlin.coroutines.coroutineContext
