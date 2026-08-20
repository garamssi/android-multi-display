package com.desklink.android.data.network

import com.desklink.android.data.security.SecureChannel
import com.desklink.android.domain.model.ProtocolConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

class PacketFramingException(message: String) : IOException(message)

class TCPClient @Inject constructor(
    private val secureChannel: SecureChannel,
) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    // Serializes writes: fire-and-forget sends on different Dispatchers.IO threads would otherwise interleave their bytes on the wire and corrupt framing. Held across the write to keep writes atomic and FIFO.
    private val sendMutex = Mutex()

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        disconnect()
        val newSocket = Socket()
        try {
            newSocket.tcpNoDelay = true
            newSocket.keepAlive = true
            newSocket.sendBufferSize = ProtocolConstants.SOCKET_BUFFER_SIZE
            newSocket.receiveBufferSize = ProtocolConstants.SOCKET_BUFFER_SIZE
            newSocket.soTimeout = 0 // blocking reads
            newSocket.connect(
                InetSocketAddress(host, port),
                ProtocolConstants.HANDSHAKE_TIMEOUT.toInt()
            )
            // secureChannel may return an SSLSocket layered over newSocket; closing the secured socket in disconnect() closes the underlying one too.
            val secured = secureChannel.secure(newSocket, host, port)
            socket = secured
            outputStream = secured.getOutputStream()
        } catch (e: Exception) {
            try {
                newSocket.close()
            } catch (_: Exception) {
            }
            socket = null
            outputStream = null
            throw e
        }
    }

    // A coroutine withTimeout cannot interrupt an already-blocked socket read (a wrong pairing PIN looks like a silent server), so cap reads at the socket level to fail fast; callers restore 0 (blocking) for the streaming loop.
    fun setReadTimeout(millis: Int) {
        socket?.soTimeout = millis
    }

    suspend fun send(type: Byte, payload: ByteArray) = sendMutex.withLock {
        withContext(Dispatchers.IO) {
            val stream = outputStream ?: throw IllegalStateException("Not connected")
            val packet = PacketFramer.frame(type, payload)
            stream.write(packet)
            stream.flush()
        }
    }

    fun receivePackets(): Flow<Pair<Byte, ByteArray>> {
        val input = socket?.getInputStream() ?: throw IllegalStateException("Not connected")
        return framedPackets(input).flowOn(Dispatchers.IO)
    }

    internal fun framedPackets(input: java.io.InputStream): Flow<Pair<Byte, ByteArray>> = flow {
        val readBuffer = ByteArray(READ_CHUNK_SIZE)

        var buffer = ByteArray(INITIAL_BUFFER_CAPACITY)
        var size = 0
        var parseOffset = 0

        while (currentCoroutineContext().isActive) {
            val bytesRead = input.read(readBuffer)
            if (bytesRead == -1) break

            if (size + bytesRead > buffer.size) {
                if (parseOffset > 0) {
                    System.arraycopy(buffer, parseOffset, buffer, 0, size - parseOffset)
                    size -= parseOffset
                    parseOffset = 0
                }
                if (size + bytesRead > buffer.size) {
                    var newCapacity = buffer.size
                    while (newCapacity < size + bytesRead) newCapacity *= 2
                    buffer = buffer.copyOf(newCapacity)
                }
            }
            System.arraycopy(readBuffer, 0, buffer, size, bytesRead)
            size += bytesRead

            var parsing = true
            while (parsing) {
                when (val result = PacketFramer.unframe(buffer, parseOffset, size - parseOffset)) {
                    is PacketFramer.UnframeResult.Success -> {
                        emit(Pair(result.type, result.payload))
                        parseOffset += result.consumed
                    }

                    is PacketFramer.UnframeResult.NeedMoreData -> parsing = false

                    is PacketFramer.UnframeResult.Error -> {
                        throw PacketFramingException("Packet framing error: ${result.message}")
                    }
                }
            }

            if (parseOffset > 0) {
                if (parseOffset == size) {
                    size = 0
                    parseOffset = 0
                } else if (parseOffset >= COMPACT_THRESHOLD) {
                    System.arraycopy(buffer, parseOffset, buffer, 0, size - parseOffset)
                    size -= parseOffset
                    parseOffset = 0
                }
            }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            outputStream?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        outputStream = null
        socket = null
    }

    private companion object {
        const val READ_CHUNK_SIZE = 64 * 1024
        const val INITIAL_BUFFER_CAPACITY = 64 * 1024

        const val COMPACT_THRESHOLD = 32 * 1024
    }
}
