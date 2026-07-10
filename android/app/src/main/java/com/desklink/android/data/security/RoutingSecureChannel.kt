package com.desklink.android.data.security

import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.TransportMode
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single [SecureChannel] the client depends on. It picks the strategy from the
 * user-selected [TransportMode] at connect time: [PlaintextSecureChannel] for USB
 * (loopback trust boundary), [TlsSecureChannel] for LAN. Mirrors how RoutingTransport
 * picks the host, so encryption follows the same per-connection decision.
 */
@Singleton
class RoutingSecureChannel @Inject constructor(
    private val settings: SettingsRepository,
    private val plaintext: PlaintextSecureChannel,
    private val tls: TlsSecureChannel,
) : SecureChannel {
    override fun secure(socket: Socket, host: String, port: Int): Socket =
        when (settings.currentTransportMode()) {
            TransportMode.USB -> plaintext.secure(socket, host, port)
            TransportMode.LAN -> tls.secure(socket, host, port)
        }
}
