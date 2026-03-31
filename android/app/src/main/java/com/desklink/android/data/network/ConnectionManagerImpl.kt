package com.desklink.android.data.network

import com.desklink.android.domain.model.ConnectionError
import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.MessageType
import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.repository.ConnectionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionManagerImpl @Inject constructor(
    private val handshakeClient: HandshakeClient,
) : ConnectionRepository {

    private val controlClient = TCPClient()
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var negotiatedConfig: DisplayConfig? = null
    private var reconnectAttempts = 0

    override suspend fun connect(config: DisplayConfig) {
        try {
            _connectionState.value = ConnectionState.Connecting

            // Connect control channel
            controlClient.connect(ProtocolConstants.PORT_CONTROL)

            // Send handshake request
            val screenWidth = config.width
            val screenHeight = config.height
            val request = handshakeClient.buildHandshakeRequest(screenWidth, screenHeight)
            controlClient.send(MessageType.HANDSHAKE_REQUEST, request)

            _connectionState.value = ConnectionState.Handshaking("Waiting for server...")

            // Wait for handshake response (read one packet)
            var handshakeAccepted = false
            var serverName = "Unknown"
            controlClient.receivePackets().collect { (type, payload) ->
                when (type) {
                    MessageType.HANDSHAKE_RESPONSE -> {
                        when (val result = handshakeClient.parseHandshakeResponse(payload)) {
                            is HandshakeClient.HandshakeResult.Accepted -> {
                                handshakeAccepted = true
                                serverName = result.serverName
                            }
                            is HandshakeClient.HandshakeResult.Rejected -> {
                                _connectionState.value = ConnectionState.Error(ConnectionError.REFUSED)
                                return@collect
                            }
                        }

                        // Send config request
                        _connectionState.value = ConnectionState.Negotiating(config)
                        val configRequest = handshakeClient.buildConfigRequest(config)
                        controlClient.send(MessageType.CONFIG_REQUEST, configRequest)
                    }

                    MessageType.CONFIG_RESPONSE -> {
                        val negotiated = handshakeClient.parseConfigResponse(payload)
                        if (negotiated != null) {
                            negotiatedConfig = negotiated
                        } else {
                            _connectionState.value = ConnectionState.Error(ConnectionError.CONFIG_NEGOTIATION_FAILED)
                            return@collect
                        }
                    }

                    MessageType.START_STREAM -> {
                        val finalConfig = negotiatedConfig ?: config
                        _connectionState.value = ConnectionState.Connected(finalConfig, serverName)
                        reconnectAttempts = 0
                        return@collect
                    }

                    MessageType.ERROR -> {
                        _connectionState.value = ConnectionState.Error(ConnectionError.REFUSED)
                        return@collect
                    }

                    else -> { /* ignore other messages during handshake */ }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(ConnectionError.TIMEOUT)
        }
    }

    override suspend fun disconnect() {
        controlClient.disconnect()
        negotiatedConfig = null
        reconnectAttempts = 0
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun reconnect() {
        val config = negotiatedConfig ?: DisplayConfig()
        if (reconnectAttempts >= ProtocolConstants.RECONNECT_MAX_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error(ConnectionError.LOST)
            return
        }

        _connectionState.value = ConnectionState.Reconnecting
        reconnectAttempts++

        val delayMs = (ProtocolConstants.RECONNECT_DELAY * (1L shl (reconnectAttempts - 1).coerceAtMost(4)))
            .coerceAtMost(ProtocolConstants.RECONNECT_MAX_DELAY)
        delay(delayMs)

        connect(config)
    }
}
