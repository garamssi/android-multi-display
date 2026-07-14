package com.desklink.android.data.security

import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaintextSecureChannel @Inject constructor() : SecureChannel {
    override fun secure(socket: Socket, host: String, port: Int): Socket = socket
}
