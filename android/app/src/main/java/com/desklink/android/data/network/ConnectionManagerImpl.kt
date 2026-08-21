package com.desklink.android.data.network

import com.desklink.android.data.device.ScreenMetricsProvider
import com.desklink.android.domain.model.ConnectionError
import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.DisplayMode
import com.desklink.android.domain.model.MessageType
import com.desklink.android.domain.model.ProtocolConstants
import android.util.Log
import com.desklink.android.data.security.PairingAuth
import com.desklink.android.data.security.PairingKeyProvider
import com.desklink.android.domain.repository.ConnectionRepository
import com.desklink.android.domain.transport.Transport
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

class PairingAuthException(message: String) : Exception(message)

@Singleton
class ConnectionManagerImpl @Inject constructor(
    private val handshakeClient: HandshakeClient,
    private val controlClient: TCPClient,
    private val transport: Transport,
    private val pairingKeyProvider: PairingKeyProvider,
    private val screenMetrics: ScreenMetricsProvider,
) : ConnectionRepository {

    internal var managerScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var negotiatedConfig: DisplayConfig? = null

    // Drives reconnect resolution: without it a reconnect falls back to DisplayConfig defaults, making the Mac thrash its virtual display between sizes.
    private var lastRequestedConfig: DisplayConfig? = null

    private var keepAlive: KeepAliveController? = null
    private var controlLoopJob: Job? = null
    private var reconnectJob: Job? = null

    // Serializes connect/disconnect/reconnect over the single shared control socket: overlapping flows would each call TCPClient.connect, whose first act closes the existing socket and tears down the other's in-flight connection.
    private val connectionMutex = Mutex()

    // onConnectionLost reconnects ONLY when this is true, so a socket closing during an intentional teardown never resurrects the session.
    @Volatile
    private var intendedConnected = false

    private var lastFailureError: ConnectionError? = null

    override suspend fun connect(config: DisplayConfig) {
        // Clear intent and cancel the reconnect loop BEFORE taking the lock so an in-flight reconnect bails and releases the lock, instead of two connects racing one socket.
        intendedConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        lastRequestedConfig = config
        connectionMutex.withLock {
            keepAlive?.stop()
            keepAlive = null
            controlLoopJob?.cancel()
            controlLoopJob = null
            if (!attemptConnect(config)) {
                _connectionState.value =
                    ConnectionState.Error(lastFailureError ?: ConnectionError.TIMEOUT)
            }
        }
    }

    // On failure records lastFailureError and returns false WITHOUT emitting an Error state, so the reconnect loop does not flicker the UI into Error between retries.
    private suspend fun attemptConnect(config: DisplayConfig): Boolean {
        lastFailureError = null
        return try {
            _connectionState.value = ConnectionState.Connecting

            Log.i(
                TAG,
                "connect(): advertising native ${config.nativeWidth}x${config.nativeHeight}, " +
                    "requesting ${config.width}x${config.height}@${config.fps}fps " +
                    "codec=${config.codec} bitrate=${config.bitrateKbps}kbps",
            )

            val host = transport.host()
            val port = transport.controlPort()
            Log.i(TAG, "connecting control channel to $host:$port")
            controlClient.connect(host, port)

            // The key has to be known before the reader starts: on LAN the server sends its
            // AUTH_CHALLENGE unprompted, and a challenge read with no key to answer it is a
            // packet thrown away.
            val session = ControlSession(config, pairingKeyProvider.currentKey())

            // ONE reader for this socket's whole life. Reading it with a fresh framed-packet
            // reader per phase loses the bytes the previous reader had already pulled off the
            // socket but not yet emitted, which desynchronizes the frame stream for good --
            // no PONG, no DISCONNECT, ever again on that connection.
            controlLoopJob = managerScope.launch { runControlReader(session) }

            // A coroutine withTimeout cannot interrupt a blocking read stuck on a silent server (the wrong-PIN case), so time-box the handshake at the socket level; blocking reads are restored below.
            controlClient.setReadTimeout(ProtocolConstants.HANDSHAKE_TIMEOUT.toInt())
            val connected = try {
                if (session.authKey != null) {
                    Log.i(TAG, "authenticating LAN connection with pairing PIN")
                    val authenticated = try {
                        withTimeout(ProtocolConstants.HANDSHAKE_TIMEOUT) {
                            session.authenticated.await()
                        }
                    } catch (_: TimeoutCancellationException) {
                        // A Mac that rejects the proof answers with nothing at all, while its
                        // PINGs keep arriving -- so a wrong PIN is indistinguishable from
                        // silence here, and silence is what it means. Reported as a timeout
                        // instead, the UI offers to retry the connection rather than asking
                        // for the new code, which is the one thing that would help.
                        throw PairingAuthException("timed out awaiting AUTH_CONFIRM")
                    }
                    if (!authenticated) {
                        // Keeps a reason the server already gave (a lockout, say); falls back
                        // to a rejection when it gave none.
                        val reason = lastFailureError ?: ConnectionError.PAIRING_REJECTED
                        throw PairingAuthException("LAN pairing failed: $reason")
                    }
                }

                Log.i(TAG, "control channel connected; sending HANDSHAKE_REQUEST")

                // Advertise the REAL native screen size so the Mac's width clamp (min(requested, advertised)) never caps the streaming resolution below the panel's true size.
                // The refresh rate is advertised for the same reason in the other direction: the Mac caps fps at what the client reports, and a fixed figure made that cap meaningless.
                val request = handshakeClient.buildHandshakeRequest(
                    screenWidth = config.nativeWidth,
                    screenHeight = config.nativeHeight,
                    maxFps = screenMetrics.maxRefreshRate(),
                )
                controlClient.send(MessageType.HANDSHAKE_REQUEST, request)

                _connectionState.value = ConnectionState.Handshaking

                withTimeout(ProtocolConstants.HANDSHAKE_TIMEOUT) {
                    session.handshaken.await()
                }
            } finally {
                controlClient.setReadTimeout(0)
            }

            if (connected && !claim(session)) {
                // Died between START_STREAM and here. Reporting Connected would leave a
                // session nobody reads; failing the attempt hands the retry to the caller.
                lastFailureError = ConnectionError.LOST
                controlClient.disconnect()
                false
            } else if (connected) {
                true
            } else {
                controlClient.disconnect()
                false
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "handshake timed out", e)
            lastFailureError = ConnectionError.TIMEOUT
            controlClient.disconnect()
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: PacketFramingException) {
            Log.e(TAG, "framing error during handshake", e)
            lastFailureError = ConnectionError.LOST
            controlClient.disconnect()
            false
        } catch (e: PairingAuthException) {
            Log.e(TAG, "pairing authentication failed", e)
            lastFailureError = lastFailureError ?: ConnectionError.PAIRING_REJECTED
            controlClient.disconnect()
            false
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            lastFailureError = ConnectionError.TIMEOUT
            controlClient.disconnect()
            false
        }
    }

    // Phases of one control connection. They share a reader, so the phase decides how a
    // packet is handled rather than which reader is attached.
    private enum class ControlPhase { AUTH, HANDSHAKE, STREAMING }

    // State of one control connection, owned by the reader coroutine and awaited by the
    // connect path through its deferreds.
    private inner class ControlSession(val config: DisplayConfig, val authKey: ByteArray?) {
        var phase = if (authKey == null) ControlPhase.HANDSHAKE else ControlPhase.AUTH
        val clientNonce: ByteArray = ByteArray(ProtocolConstants.AUTH_NONCE_LENGTH)
            .also { SecureRandom().nextBytes(it) }
        var serverNonce: ByteArray? = null
        var serverName = "Unknown"
        var displayMode = DisplayMode.DEFAULT
        val authenticated = CompletableDeferred<Boolean>()
        val handshaken = CompletableDeferred<Boolean>()

        // Set once the connect path has taken ownership of this session, and once the reader
        // has seen it die. Together they close the window between START_STREAM and the
        // attempt returning: a socket that dies in there must fail the attempt, not be
        // reported as a live session with nobody watching it.
        val established = AtomicBoolean(false)
        val lost = AtomicBoolean(false)

        // Releases whoever is waiting so a dead socket surfaces as a failed attempt rather
        // than as a five-second timeout.
        fun abandon() {
            authenticated.complete(false)
            handshaken.complete(false)
        }
    }

    private suspend fun runControlReader(session: ControlSession) {
        try {
            controlClient.receivePackets().collect { (type, payload) ->
                // Answered in every phase: the server pings from the moment the socket opens,
                // and a phase that ignores pings makes the server declare the client gone.
                if (type == MessageType.PING) {
                    controlClient.send(MessageType.PONG, payload)
                    return@collect
                }
                when (session.phase) {
                    ControlPhase.AUTH -> handleAuthPacket(session, type, payload)
                    ControlPhase.HANDSHAKE -> handleHandshakePacket(session, type, payload)
                    ControlPhase.STREAMING -> handleStreamingPacket(session, type, payload)
                }
            }
            Log.i(TAG, "control stream ended")
            noteLost(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Which path detects the loss decides how long the user waits, so name it.
            Log.i(TAG, "control read failed (${e.javaClass.simpleName})")
            noteLost(session)
        }
    }

    // The one place a dead control connection is recorded. Whether it also starts a reconnect
    // depends on who owns the session: before the connect path has claimed it, the attempt
    // itself must fail, or a reconnect would race the attempt that is still running.
    private fun noteLost(session: ControlSession) {
        session.lost.set(true)
        session.abandon()
        if (session.established.get()) onConnectionLost()
    }

    private suspend fun handleAuthPacket(session: ControlSession, type: Byte, payload: ByteArray) {
        val key = session.authKey ?: return
        when (type) {
            MessageType.AUTH_CHALLENGE -> {
                if (payload.size != ProtocolConstants.AUTH_NONCE_LENGTH) {
                    session.authenticated.complete(false)
                    return
                }
                session.serverNonce = payload
                val proof = PairingAuth.clientProof(key, payload, session.clientNonce)
                controlClient.send(MessageType.AUTH_RESPONSE, session.clientNonce + proof)
            }

            MessageType.AUTH_CONFIRM -> {
                val serverNonce = session.serverNonce
                val ok = serverNonce != null &&
                    PairingAuth.verify(
                        payload,
                        PairingAuth.serverProof(key, serverNonce, session.clientNonce),
                    )
                if (ok) session.phase = ControlPhase.HANDSHAKE
                session.authenticated.complete(ok)
            }

            MessageType.ERROR -> {
                // The Mac says why. Acting on it now is the difference between a named cause
                // and five seconds of a hung-looking connection followed by an offer to retry
                // the network -- and 1005 means the code was never the problem.
                lastFailureError = handshakeClient.parseErrorCode(payload)
                    ?: ConnectionError.PAIRING_REJECTED
                session.authenticated.complete(false)
            }

            else -> { /* nothing else is meaningful before the client is authenticated */ }
        }
    }

    private suspend fun handleHandshakePacket(
        session: ControlSession,
        type: Byte,
        payload: ByteArray,
    ) {
        Log.i(TAG, "control rx type=0x${type.toInt().and(0xFF).toString(16)}")
        when (type) {
            MessageType.HANDSHAKE_RESPONSE -> {
                when (val result = handshakeClient.parseHandshakeResponse(payload)) {
                    is HandshakeClient.HandshakeResult.Accepted -> {
                        session.serverName = result.serverName
                        session.displayMode = result.displayMode
                    }

                    is HandshakeClient.HandshakeResult.Rejected -> {
                        lastFailureError = ConnectionError.REFUSED
                        session.handshaken.complete(false)
                        return
                    }

                    is HandshakeClient.HandshakeResult.Failed -> {
                        lastFailureError = result.error
                        session.handshaken.complete(false)
                        return
                    }
                }

                _connectionState.value = ConnectionState.Negotiating(session.config)
                val configRequest = handshakeClient.buildConfigRequest(session.config)
                controlClient.send(MessageType.CONFIG_REQUEST, configRequest)
            }

            MessageType.CONFIG_RESPONSE -> {
                val negotiated = handshakeClient.parseConfigResponse(payload)
                if (negotiated != null) {
                    // Preserve the native size (CONFIG_RESPONSE echoes only the negotiated streaming resolution) so a later reconnect still advertises the true panel size.
                    negotiatedConfig = negotiated.copy(
                        nativeWidth = session.config.nativeWidth,
                        nativeHeight = session.config.nativeHeight,
                    )
                } else {
                    _connectionState.value =
                        ConnectionState.Error(ConnectionError.CONFIG_NEGOTIATION_FAILED)
                    session.handshaken.complete(false)
                }
            }

            MessageType.START_STREAM -> {
                val finalConfig = negotiatedConfig ?: session.config
                Log.i(TAG, "START_STREAM received -> Connected (${session.serverName})")
                _connectionState.value =
                    ConnectionState.Connected(finalConfig, session.serverName, session.displayMode)
                // The reader owns the phase: flipping it from the connect path would leave
                // packets that arrive in this same read dispatched against the old phase.
                session.phase = ControlPhase.STREAMING
                session.handshaken.complete(true)
            }

            MessageType.DISCONNECT -> {
                // The server changed its mind between START_STREAM and here; treat it as a
                // failed attempt so the reconnect loop, not this attempt, owns the retry.
                lastFailureError = ConnectionError.LOST
                session.handshaken.complete(false)
            }

            MessageType.ERROR -> {
                lastFailureError = ConnectionError.REFUSED
                session.handshaken.complete(false)
            }

            else -> { /* ignore other messages during the handshake */ }
        }
    }

    private suspend fun handleStreamingPacket(
        session: ControlSession,
        type: Byte,
        payload: ByteArray,
    ) {
        if (keepAlive?.onPacket(type, payload) == true) return
        when (type) {
            MessageType.DISCONNECT -> {
                Log.i(TAG, "DISCONNECT received")
                noteLost(session)
            }

            MessageType.ERROR ->
                _connectionState.value = ConnectionState.Error(ConnectionError.LOST)

            else -> { /* video/input handled on their own channels */ }
        }
    }

    // Takes ownership of a handshaken session. Returns false when the reader already died,
    // in which case nothing is started and the caller fails the attempt.
    private fun claim(session: ControlSession): Boolean {
        session.established.set(true)
        if (session.lost.get()) {
            controlLoopJob?.cancel()
            controlLoopJob = null
            return false
        }
        // Set intent BEFORE the keep-alive starts so a loss it detects immediately is treated as unintentional (and reconnects), closing a startup race.
        intendedConnected = true
        startKeepAlive()
        return true
    }

    private fun startKeepAlive() {
        val ka = KeepAliveController(
            scope = managerScope,
            send = { type, payload -> controlClient.send(type, payload) },
            onConnectionLost = { onConnectionLost() },
        )
        keepAlive = ka
        ka.start()
    }


    private fun onConnectionLost() {
        keepAlive?.stop()
        controlLoopJob?.cancel()
        controlLoopJob = null
        if (!intendedConnected) return
        // Ignore a second loss while a reconnect loop is already running so loops never stack.
        if (reconnectJob?.isActive == true) return
        reconnectJob = managerScope.launch { reconnectLoop() }
    }

    private suspend fun reconnectLoop() {
        val config = negotiatedConfig ?: lastRequestedConfig ?: DisplayConfig()
        for (delayMillis in ProtocolConstants.RECONNECT_DELAYS_MS) {
            _connectionState.value = ConnectionState.Reconnecting
            delay(delayMillis)
            if (!intendedConnected) return
            val connected = connectionMutex.withLock {
                if (!intendedConnected) false else attemptConnect(config)
            }
            if (connected) return
        }
        if (intendedConnected) {
            _connectionState.value = ConnectionState.Error(ConnectionError.LOST)
        }
    }

    override suspend fun disconnect() {
        // Drop intent and kill the reconnect loop BEFORE taking the lock so a late socket-close can't spawn a new reconnect after teardown.
        intendedConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        connectionMutex.withLock {
            keepAlive?.stop()
            keepAlive = null
            controlLoopJob?.cancel()
            controlLoopJob = null
            controlClient.disconnect()
            negotiatedConfig = null
            lastRequestedConfig = null
            lastFailureError = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override suspend fun reconnect() {
        if (reconnectJob?.isActive == true) return
        intendedConnected = true
        reconnectLoop()
    }

    private companion object {
        const val TAG = "DeskLink"
    }
}
