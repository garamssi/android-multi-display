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

/**
 * Thrown when the received byte stream cannot be framed per the protocol
 * (e.g. an impossibly large or invalid length field). This is a *typed*
 * failure so callers (the connection manager) can distinguish protocol
 * corruption from ordinary I/O errors and trigger a reconnect instead of
 * crashing on a raw [RuntimeException].
 */
class PacketFramingException(message: String) : IOException(message)

/**
 * TCP client for connecting to the Mac server.
 *
 * The `host` is supplied by the caller (resolved from the active
 * [com.desklink.android.domain.transport.Transport]): USB dials the device's loopback
 * (`127.0.0.1`), which ADB reverse-tunnels to the Mac; a LAN transport supplies the
 * Mac's IP. This client is transport-agnostic — it just dials `host:port`.
 *
 * After connecting, the raw socket is handed to the injected [SecureChannel]: USB returns
 * it unchanged (plaintext); LAN wraps it in TLS. All framing then runs over the returned
 * socket's streams, unaware of which it got.
 */
class TCPClient @Inject constructor(
    private val secureChannel: SecureChannel,
) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    /**
     * Serializes writes. Touch/scroll sends are fire-and-forget (one coroutine per event),
     * so without this two rapid sends could run on different `Dispatchers.IO` threads and
     * interleave their bytes on the wire (corrupting the peer's framing) or complete out of
     * order. The lock is held across the write, and callers acquire it in the order they
     * were launched — so writes are atomic and stay FIFO (dispatch order == on-wire order).
     */
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
            // Wrap with the channel's security (plaintext for USB, TLS for LAN). The
            // returned socket may be an SSLSocket layered over newSocket; closing it in
            // disconnect() closes the underlying socket too.
            val secured = secureChannel.secure(newSocket, host, port)
            socket = secured
            outputStream = secured.getOutputStream()
        } catch (e: Exception) {
            // Never leak a half-open socket on a failed connect (A-H3).
            try {
                newSocket.close()
            } catch (_: Exception) {
            }
            socket = null
            outputStream = null
            throw e
        }
    }

    /**
     * Bounds subsequent blocking reads to [millis] (0 = block indefinitely). A coroutine
     * `withTimeout` cannot interrupt a socket read that is already blocked waiting on a
     * silent server — which is exactly what a wrong pairing PIN looks like, since the
     * server answers a bad PIN with silence. Capping the read at the socket level lets the
     * pairing/handshake fail fast; callers restore blocking reads (0) for the long-lived
     * streaming control loop.
     */
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

    /**
     * Returns a cold [Flow] that emits received packets as (type, payload) pairs.
     *
     * Reassembly strategy (A-C1): incoming bytes are appended to a single growable
     * buffer and parsed via a moving [parseOffset]. We do **not** copy the whole
     * accumulator on every read (which was O(n²) for large frames); instead we only
     * compact — dropping already-consumed bytes — once at least one full packet has
     * been consumed from the front. Partial reads accumulate until a complete frame
     * is available, so multi-frame reassembly across tiny chunks and single large
     * frames both parse correctly.
     *
     * On a framing error the flow terminates by throwing [PacketFramingException]
     * (a typed failure, A-H3) rather than a raw [RuntimeException]; the caller closes
     * the socket and triggers reconnect.
     */
    fun receivePackets(): Flow<Pair<Byte, ByteArray>> {
        val input = socket?.getInputStream() ?: throw IllegalStateException("Not connected")
        return framedPackets(input).flowOn(Dispatchers.IO)
    }

    /**
     * Frames packets out of an arbitrary [InputStream]. Extracted from
     * [receivePackets] so the reassembly logic can be unit-tested with a mock
     * stream that delivers bytes in tiny chunks. Not intended for external use.
     */
    internal fun framedPackets(input: java.io.InputStream): Flow<Pair<Byte, ByteArray>> = flow {
        val readBuffer = ByteArray(READ_CHUNK_SIZE)

        // Growable accumulation buffer. `size` is the count of valid bytes;
        // `parseOffset` is how far we have consumed from the front.
        var buffer = ByteArray(INITIAL_BUFFER_CAPACITY)
        var size = 0
        var parseOffset = 0

        while (currentCoroutineContext().isActive) {
            val bytesRead = input.read(readBuffer)
            if (bytesRead == -1) break

            // Ensure capacity for the freshly read bytes, compacting first if that
            // is enough to make room (avoids unbounded growth for streaming frames).
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

            // Drain as many complete packets as are currently buffered.
            var parsing = true
            while (parsing) {
                when (val result = PacketFramer.unframe(buffer, parseOffset, size - parseOffset)) {
                    is PacketFramer.UnframeResult.Success -> {
                        emit(Pair(result.type, result.payload))
                        parseOffset += result.consumed
                    }

                    is PacketFramer.UnframeResult.NeedMoreData -> parsing = false

                    is PacketFramer.UnframeResult.Error -> {
                        // Typed, cooperative failure: close cleanly and let the
                        // connection manager decide to reconnect.
                        throw PacketFramingException("Packet framing error: ${result.message}")
                    }
                }
            }

            // Compact once a full packet was consumed so unconsumed bytes move to
            // the front. This is the ONLY place we shift data, and only after real
            // progress — never a per-read full copy.
            if (parseOffset > 0) {
                if (parseOffset == size) {
                    // Everything consumed — cheap reset, no copy.
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

        /** Only pay the compaction memmove once the consumed prefix is sizeable. */
        const val COMPACT_THRESHOLD = 32 * 1024
    }
}
