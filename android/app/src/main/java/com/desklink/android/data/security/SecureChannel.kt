package com.desklink.android.data.security

import java.net.Socket

/**
 * Wraps a freshly connected [Socket] with the transport's security for its channel.
 *
 * USB returns the socket unchanged (plaintext over the adb-reverse loopback); the LAN
 * implementation wraps it in a TLS SSLSocket after the handshake. The framing/protocol
 * code above reads the returned socket's streams and is unaware which one it got, so
 * adding encryption stays a leaf change. Called on an IO dispatcher — the implementation
 * may block (e.g. a TLS handshake).
 */
interface SecureChannel {
    fun secure(socket: Socket, host: String, port: Int): Socket
}
