package com.desklink.android.data.security

import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-op [SecureChannel]: the socket is used as-is. This is USB (plaintext over the
 * adb-reverse loopback, where the physical link is the trust boundary) and, until the
 * TLS channel lands, LAN as well — so introducing the abstraction changes no behavior.
 */
@Singleton
class PlaintextSecureChannel @Inject constructor() : SecureChannel {
    override fun secure(socket: Socket, host: String, port: Int): Socket = socket
}
