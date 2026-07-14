package com.desklink.android.data.security

import java.net.Socket

interface SecureChannel {
    fun secure(socket: Socket, host: String, port: Int): Socket
}
