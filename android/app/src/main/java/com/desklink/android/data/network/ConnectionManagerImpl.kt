package com.desklink.android.data.network

import com.desklink.android.domain.model.ConnectionError
import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.MessageType
import com.desklink.android.domain.model.ProtocolConstants
import android.util.Log
import com.desklink.android.data.security.PairingAuth
import com.desklink.android.data.security.PairingKeyProvider
import com.desklink.android.domain.repository.ConnectionRepository
import com.desklink.android.domain.transport.Transport
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
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

            // A coroutine withTimeout cannot interrupt a blocking read stuck on a silent server (the wrong-PIN case), so time-box the handshake at the socket level; blocking reads are restored below.
            controlClient.setReadTimeout(ProtocolConstants.HANDSHAKE_TIMEOUT.toInt())
            val connected = try {
                pairingKeyProvider.currentKey()?.let { key ->
                    Log.i(TAG, "authenticating LAN connection with pairing PIN")
                    runClientAuth(key)
                }

                Log.i(TAG, "control channel connected; sending HANDSHAKE_REQUEST")

                // Advertise the REAL native screen size so the Mac's width clamp (min(requested, advertised)) never caps the streaming resolution below the panel's true size.
                val request = handshakeClient.buildHandshakeRequest(config.nativeWidth, config.nativeHeight)
                controlClient.send(MessageType.HANDSHAKE_REQUEST, request)

                _connectionState.value = ConnectionState.Handshaking

                withTimeout(ProtocolConstants.HANDSHAKE_TIMEOUT) {
                    runHandshake(config)
                }
            } finally {
                controlClient.setReadTimeout(0)
            }

            if (connected) {
                // Set intent BEFORE starting the control loop so a loss the loop detects immediately is treated as unintentional (and reconnects), closing a startup race.
                intendedConnected = true
                // Start the reader + keep-alive only AFTER the handshake collection ends, so two collectors never read the same socket InputStream concurrently.
                startControlLoop()
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
            lastFailureError = ConnectionError.PAIRING_REJECTED
            controlClient.disconnect()
            false
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            lastFailureError = ConnectionError.TIMEOUT
            controlClient.disconnect()
            false
        }
    }

    private suspend fun runHandshake(config: DisplayConfig): Boolean {
        var handshakeComplete = false
        var connected = false
        var serverName = "Unknown"

        controlClient.receivePackets()
            .takeWhile { !handshakeComplete }
            .collect { (type, payload) ->
                Log.i(TAG, "control rx type=0x${type.toInt().and(0xFF).toString(16)}")
                when (type) {
                    MessageType.HANDSHAKE_RESPONSE -> {
                        when (val result = handshakeClient.parseHandshakeResponse(payload)) {
                            is HandshakeClient.HandshakeResult.Accepted -> {
                                serverName = result.serverName
                            }

                            is HandshakeClient.HandshakeResult.Rejected -> {
                                lastFailureError = ConnectionError.REFUSED
                                handshakeComplete = true
                                return@collect
                            }

                            is HandshakeClient.HandshakeResult.Failed -> {
                                lastFailureError = result.error
                                handshakeComplete = true
                                return@collect
                            }
                        }

                        _connectionState.value = ConnectionState.Negotiating(config)
                        val configRequest = handshakeClient.buildConfigRequest(config)
                        controlClient.send(MessageType.CONFIG_REQUEST, configRequest)
                    }

                    MessageType.CONFIG_RESPONSE -> {
                        val negotiated = handshakeClient.parseConfigResponse(payload)
                        if (negotiated != null) {
                            // Preserve the native size (CONFIG_RESPONSE echoes only the negotiated streaming resolution) so a later reconnect still advertises the true panel size.
                            negotiatedConfig = negotiated.copy(
                                nativeWidth = config.nativeWidth,
                                nativeHeight = config.nativeHeight,
                            )
                        } else {
                            _connectionState.value =
                                ConnectionState.Error(ConnectionError.CONFIG_NEGOTIATION_FAILED)
                            handshakeComplete = true
                        }
                    }

                    MessageType.START_STREAM -> {
                        val finalConfig = negotiatedConfig ?: config
                        Log.i(TAG, "START_STREAM received -> Connected ($serverName)")
                        _connectionState.value = ConnectionState.Connected(finalConfig, serverName)
                        connected = true
                        handshakeComplete = true
                    }

                    MessageType.ERROR -> {
                        lastFailureError = ConnectionError.REFUSED
                        handshakeComplete = true
                    }

                    else -> { /* ignore other messages during handshake */ }
                }
            }
        return connected
    }

    // This packet collection completes before runHandshake starts its own, so the two never read the socket concurrently.
    private suspend fun runClientAuth(key: ByteArray) {
        val clientNonce = ByteArray(ProtocolConstants.AUTH_NONCE_LENGTH)
            .also { SecureRandom().nextBytes(it) }
        var serverNonce: ByteArray? = null
        var authenticated = false
        var done = false

        // Wall-clock deadline: the server PINGs an unauthenticated client every second, so a wrong PIN yields a steady frame stream (never a read timeout) that would otherwise wait forever for an AUTH_CONFIRM.
        val deadlineNanos = System.nanoTime() + ProtocolConstants.HANDSHAKE_TIMEOUT * 1_000_000L

        try {
            controlClient.receivePackets()
                .takeWhile { !done }
                .collect { (type, payload) ->
                    when (type) {
                        MessageType.AUTH_CHALLENGE -> {
                            if (payload.size != ProtocolConstants.AUTH_NONCE_LENGTH) {
                                done = true
                                return@collect
                            }
                            serverNonce = payload
                            val proof = PairingAuth.clientProof(key, payload, clientNonce)
                            controlClient.send(MessageType.AUTH_RESPONSE, clientNonce + proof)
                        }

                        MessageType.AUTH_CONFIRM -> {
                            val sNonce = serverNonce
                            if (sNonce != null &&
                                PairingAuth.verify(payload, PairingAuth.serverProof(key, sNonce, clientNonce))
                            ) {
                                authenticated = true
                            }
                            done = true
                        }

                        else -> { /* ignore keep-alive/other frames until authenticated */ }
                    }
                    if (!authenticated && System.nanoTime() >= deadlineNanos) {
                        throw PairingAuthException("pairing timed out awaiting AUTH_CONFIRM")
                    }
                }
        } catch (_: java.net.SocketTimeoutException) {
            // A read timeout after auth succeeded is only the trailing read that lets takeWhile stop, so it is not a failure; incomplete auth means the server went silent.
            if (!authenticated) throw PairingAuthException("pairing timed out awaiting AUTH_CONFIRM")
        }

        if (!authenticated) throw PairingAuthException("LAN pairing authentication failed")
    }

    private fun startControlLoop() {
        controlLoopJob?.cancel()
        val ka = KeepAliveController(
            scope = managerScope,
            send = { type, payload -> controlClient.send(type, payload) },
            onConnectionLost = { onConnectionLost() },
        )
        keepAlive = ka
        ka.start()

        controlLoopJob = managerScope.launch {
            try {
                controlClient.receivePackets().collect { (type, payload) ->
                    if (keepAlive?.onPacket(type, payload) == true) return@collect
                    when (type) {
                        MessageType.DISCONNECT -> onConnectionLost()
                        MessageType.ERROR ->
                            _connectionState.value = ConnectionState.Error(ConnectionError.LOST)
                        else -> { /* video/input handled on their own channels */ }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onConnectionLost()
            }
        }
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
        var attempt = 0
        while (attempt < ProtocolConstants.RECONNECT_MAX_ATTEMPTS) {
            attempt++
            _connectionState.value = ConnectionState.Reconnecting
            delay(ProtocolConstants.RECONNECT_DELAY)
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
