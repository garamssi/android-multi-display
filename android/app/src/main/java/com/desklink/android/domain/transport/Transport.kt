package com.desklink.android.domain.transport

/**
 * Resolves the network target the client should connect to, abstracting away HOW the
 * peer is reached (USB via adb-reverse loopback, or LAN via the Mac's IP).
 *
 * This is the seam that removes the previously hardcoded `127.0.0.1` from the socket
 * layer: the connection/video/input channels ask the transport for the host and dial
 * it, so a future LAN transport can supply a discovered/entered IP without any change
 * to the framing, handshake, or streaming code. All three channels share one transport
 * instance so they always target the same server.
 *
 * `host()` is `suspend` because a LAN transport may need to await discovery; the USB
 * transport returns a constant.
 */
interface Transport {
    /** The host to dial for every channel (control/video/input use the same host). */
    suspend fun host(): String

    /**
     * Ports the channels dial. These differ by transport: USB uses the loopback-tunnelled
     * 7100-7102; LAN uses a separate 7110-7112 set so the Mac can run a plaintext USB
     * listener and a TLS+PIN LAN listener simultaneously. Resolved per connect (like
     * [host]) so switching mode in Settings takes effect on the next connect.
     */
    fun controlPort(): Int

    fun videoPort(): Int

    fun inputPort(): Int
}
