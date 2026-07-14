package com.desklink.android.data.security

import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.TransportMode
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

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
