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

/** Thrown when LAN pairing authentication fails (wrong/absent PIN or a rejecting/rogue server). */
class PairingAuthException(message: String) : Exception(message)

@Singleton
class ConnectionManagerImpl @Inject constructor(
    private val handshakeClient: HandshakeClient,
    private val controlClient: TCPClient,
    private val transport: Transport,
    private val pairingKeyProvider: PairingKeyProvider,
) : ConnectionRepository {

    /**
     * Scope for background work owned by the manager (post-handshake control-channel
     * reader + keep-alive). Package-visible with a default so tests can substitute a
     * test scope. Uses a SupervisorJob so one failed child doesn't cancel siblings.
     */
    internal var managerScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var negotiatedConfig: DisplayConfig? = null

    /**
     * The config of the most recent user-initiated [connect], used to drive reconnect
     * attempts. Without this, a reconnect that runs before/without a completed
     * negotiation would fall back to [DisplayConfig]'s defaults (a different
     * resolution), making the Mac thrash its virtual display between sizes.
     */
    private var lastRequestedConfig: DisplayConfig? = null

    private var keepAlive: KeepAliveController? = null
    private var controlLoopJob: Job? = null
    private var reconnectJob: Job? = null

    /**
     * Serializes [connect]/[disconnect]/reconnect so at most one of them drives the
     * single shared control socket at a time. Two overlapping flows (e.g. an auto
     * reconnect and a user-initiated connect) would otherwise each call
     * [TCPClient.connect], whose first act is to close the existing socket — tearing
     * down the other's in-flight connection.
     */
    private val connectionMutex = Mutex()

    /**
     * Whether we currently intend to hold a connection. Set true on a successful
     * connect, false by [disconnect] and while a fresh [connect] supersedes an old
     * session. [onConnectionLost] reconnects ONLY when this is true, so the old
     * socket closing during an intentional teardown never resurrects the session.
     */
    @Volatile
    private var intendedConnected = false

    /** Error from the most recent [attemptConnect] failure, surfaced by the caller. */
    private var lastFailureError: ConnectionError? = null

    override suspend fun connect(config: DisplayConfig) {
        // A user-initiated connect supersedes any auto-reconnect. Clear the intent and
        // cancel the loop BEFORE taking the lock so an in-flight reconnect attempt bails
        // and releases the lock promptly, instead of two connects racing one socket.
        intendedConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        lastRequestedConfig = config
        connectionMutex.withLock {
            // Tear any prior session down first; that socket closing must NOT be read as
            // a loss (intendedConnected is false here, so onConnectionLost won't reconnect).
            keepAlive?.stop()
            keepAlive = null
            controlLoopJob?.cancel()
            controlLoopJob = null
            // attemptConnect sets intendedConnected on success (before its control loop
            // starts); on failure the intent stays false and we surface the error.
            if (!attemptConnect(config)) {
                _connectionState.value =
                    ConnectionState.Error(lastFailureError ?: ConnectionError.TIMEOUT)
            }
        }
    }

    /**
     * Performs one connect + handshake attempt. Emits only progress/success states
     * (Connecting -> Handshaking -> Negotiating -> Connected); on failure it records
     * [lastFailureError] and returns false WITHOUT emitting an Error state, so callers
     * (initial connect vs the reconnect loop) decide whether the failure is terminal.
     * This keeps the reconnect loop from flickering the UI into Error between retries.
     *
     * @return true if the attempt reached the Connected state.
     */
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

            // Connect control channel to the transport-resolved host (USB loopback / LAN IP).
            val host = transport.host()
            val port = transport.controlPort()
            Log.i(TAG, "connecting control channel to $host:$port")
            controlClient.connect(host, port)

            // Time-box the synchronous connect handshake (pairing auth + handshake) at the
            // socket level. With blocking reads (soTimeout=0) a coroutine withTimeout cannot
            // interrupt a read stuck on a silent server — the wrong-PIN case, which the
            // server answers with silence — so the read timeout is what actually unblocks
            // it. Restored to blocking reads for the long-lived streaming control loop.
            controlClient.setReadTimeout(ProtocolConstants.HANDSHAKE_TIMEOUT.toInt())
            val connected = try {
                // LAN: mutually authenticate with the pairing PIN over the (TLS) control
                // channel before the handshake. USB returns a null key and skips this.
                pairingKeyProvider.currentKey()?.let { key ->
                    Log.i(TAG, "authenticating LAN connection with pairing PIN")
                    runClientAuth(key)
                }

                Log.i(TAG, "control channel connected; sending HANDSHAKE_REQUEST")

                // Advertise the device's REAL native screen size so the Mac's width clamp
                // (min(requestedWidth, advertisedScreenWidth)) never caps the requested
                // streaming resolution below the panel's true size.
                val request = handshakeClient.buildHandshakeRequest(config.nativeWidth, config.nativeHeight)
                controlClient.send(MessageType.HANDSHAKE_REQUEST, request)

                _connectionState.value = ConnectionState.Handshaking

                // A-H1: bound the whole handshake exchange by HANDSHAKE_TIMEOUT.
                withTimeout(ProtocolConstants.HANDSHAKE_TIMEOUT) {
                    runHandshake(config)
                }
            } finally {
                controlClient.setReadTimeout(0)
            }

            if (connected) {
                // We are connected and now intend to STAY connected. Set this BEFORE
                // starting the control loop so a loss the loop detects immediately is
                // treated as unintentional (and reconnects), closing a startup race.
                intendedConnected = true
                // Start the persistent control-channel reader + keep-alive only AFTER
                // the handshake flow has fully terminated, so we never have two
                // concurrent collectors reading the same socket InputStream.
                startControlLoop()
                true
            } else {
                controlClient.disconnect()
                false
            }
        } catch (e: TimeoutCancellationException) {
            // A-H1: handshake did not complete in time.
            Log.e(TAG, "handshake timed out", e)
            lastFailureError = ConnectionError.TIMEOUT
            controlClient.disconnect()
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: PacketFramingException) {
            // Corrupt stream during handshake.
            Log.e(TAG, "framing error during handshake", e)
            lastFailureError = ConnectionError.LOST
            controlClient.disconnect()
            false
        } catch (e: PairingAuthException) {
            // LAN pairing failed (wrong/absent PIN, or server rejected us). Distinct from a
            // generic REFUSED so the UI can show the wrong-PIN retry flow.
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

    /**
     * Runs the request/response handshake, collecting control packets until the
     * exchange completes (Connected) or fails.
     * @return true if the connection reached the Connected state.
     */
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
                                // A-L7: e.g. PROTOCOL_MISMATCH or malformed JSON.
                                lastFailureError = result.error
                                handshakeComplete = true
                                return@collect
                            }
                        }

                        // Send config request.
                        _connectionState.value = ConnectionState.Negotiating(config)
                        val configRequest = handshakeClient.buildConfigRequest(config)
                        controlClient.send(MessageType.CONFIG_REQUEST, configRequest)
                    }

                    MessageType.CONFIG_RESPONSE -> {
                        val negotiated = handshakeClient.parseConfigResponse(payload)
                        if (negotiated != null) {
                            // Preserve the device's native size (the CONFIG_RESPONSE only
                            // echoes the negotiated streaming resolution) so a later
                            // reconnect still advertises the true panel size.
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

    /**
     * LAN mutual authentication over the (TLS) control channel, before the handshake.
     * Server → AUTH_CHALLENGE(serverNonce); we reply AUTH_RESPONSE(clientNonce + proof);
     * server → AUTH_CONFIRM(serverProof) which we verify. Throws [PairingAuthException]
     * if the server rejects us (no CONFIRM) or its proof is wrong (impersonation). This
     * collection completes before [runHandshake] starts its own, so the two never read
     * the socket concurrently.
     */
    private suspend fun runClientAuth(key: ByteArray) {
        val clientNonce = ByteArray(ProtocolConstants.AUTH_NONCE_LENGTH)
            .also { SecureRandom().nextBytes(it) }
        var serverNonce: ByteArray? = null
        var authenticated = false
        var done = false

        // Wall-clock deadline for the whole exchange. The server keep-alives (PINGs) an
        // unauthenticated client every second, so a wrong PIN produces a steady frame
        // stream (never a read timeout) that would otherwise wait forever for an
        // AUTH_CONFIRM that never comes. The socket read timeout (soTimeout, set by the
        // caller) covers the opposite case where the server goes fully silent.
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
                    // Stop waiting once the pairing window elapses without a confirm, even
                    // if keep-alive frames keep arriving.
                    if (!authenticated && System.nanoTime() >= deadlineNanos) {
                        throw PairingAuthException("pairing timed out awaiting AUTH_CONFIRM")
                    }
                }
        } catch (_: java.net.SocketTimeoutException) {
            // A read timeout with auth still incomplete means the server went silent within
            // the pairing window (wrong PIN, or it went away) — fail instead of hanging. If
            // auth already succeeded, the timeout is only the trailing read that lets
            // takeWhile stop, so it is not a failure.
            if (!authenticated) throw PairingAuthException("pairing timed out awaiting AUTH_CONFIRM")
        }

        if (!authenticated) throw PairingAuthException("LAN pairing authentication failed")
    }

    /**
     * After a successful handshake, keep reading the control channel and run the
     * keep-alive (A-H2). Inbound PING/PONG are handled by [KeepAliveController];
     * DISCONNECT/ERROR trigger a lost/error transition. A framing or I/O error, or
     * a keep-alive timeout, transitions to Error(LOST) and triggers reconnect.
     */
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
        // Only an UNINTENTIONAL loss (keep-alive timeout, cable pull) while we still
        // intend to be connected should auto-reconnect. After disconnect() or a
        // superseding connect(), the old socket closing must not resurrect the session —
        // that previously started a reconnect with a stale default config and raced the
        // user's own connect on the shared socket.
        if (!intendedConnected) return
        // Start (once) a bounded reconnect loop. A second loss while a loop is
        // already running is ignored so we never stack overlapping loops.
        if (reconnectJob?.isActive == true) return
        reconnectJob = managerScope.launch { reconnectLoop() }
    }

    /**
     * A-H3: bounded live reconnect. Shows [ConnectionState.Reconnecting] and retries
     * [ProtocolConstants.RECONNECT_MAX_ATTEMPTS] times at a fixed
     * [ProtocolConstants.RECONNECT_DELAY] interval. On success it returns silently
     * (the attempt has already published Connected); once the attempts are exhausted
     * it publishes a terminal [ConnectionState.Error] so the UI can leave the mirror
     * screen. Intermediate attempt failures never publish Error (see [attemptConnect]).
     */
    private suspend fun reconnectLoop() {
        // Prefer the negotiated config; fall back to the last user-requested one so a
        // reconnect never advertises a different resolution than the live session did.
        val config = negotiatedConfig ?: lastRequestedConfig ?: DisplayConfig()
        var attempt = 0
        while (attempt < ProtocolConstants.RECONNECT_MAX_ATTEMPTS) {
            attempt++
            _connectionState.value = ConnectionState.Reconnecting
            delay(ProtocolConstants.RECONNECT_DELAY)
            // A user connect()/disconnect() may have superseded us during the delay.
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
        // Drop the intent and kill the reconnect loop before taking the lock so a
        // late socket-close can't spawn a new reconnect after we tear down.
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
        // A manual reconnect is an explicit intent to hold the connection.
        intendedConnected = true
        reconnectLoop()
    }

    private companion object {
        const val TAG = "DeskLink"
    }
}
