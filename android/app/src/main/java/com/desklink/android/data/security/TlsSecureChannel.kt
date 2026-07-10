package com.desklink.android.data.security

import android.util.Log
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

private const val TAG = "DeskLink"

/**
 * LAN [SecureChannel]: layers TLS over the already-connected socket. The Mac serves a
 * self-signed certificate; we pin it trust-on-first-use ([CertPinStore]) since a public
 * CA can't vouch for a self-signed LAN cert. Hostname is intentionally not validated —
 * identity comes from the pin plus (next step) the PIN pairing. The handshake runs on
 * the caller's IO dispatcher.
 */
@Singleton
class TlsSecureChannel @Inject constructor(
    private val pinStore: CertPinStore,
) : SecureChannel {

    override fun secure(socket: Socket, host: String, port: Int): Socket {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(PinningTrustManager(pinStore, host)), null)

        val sslSocket = context.socketFactory
            .createSocket(socket, host, port, /* autoClose = */ true) as SSLSocket
        sslSocket.useClientMode = true
        // TLS 1.2/1.3 only (1.3 is available from API 29; on API 28 this yields 1.2).
        sslSocket.enabledProtocols = sslSocket.supportedProtocols
            .filter { it == "TLSv1.3" || it == "TLSv1.2" }
            .toTypedArray()
        sslSocket.startHandshake()
        return sslSocket
    }
}

/**
 * Trust-on-first-use certificate pinning: on first connect to a host, trust and remember
 * the leaf certificate's fingerprint; thereafter require the same one. Hostname is not
 * checked (self-signed cert); the pin + PIN pairing provide identity.
 */
private class PinningTrustManager(
    private val pinStore: CertPinStore,
    private val host: String,
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client authentication is not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("empty server certificate chain")
        val fingerprint = CertPinStore.fingerprint(leaf.encoded)
        when (val pinned = pinStore.pinFor(host)) {
            null -> {
                pinStore.setPin(host, fingerprint) // TOFU: trust and remember
                Log.i(TAG, "pinned TLS certificate for $host")
            }
            fingerprint -> Unit // matches the pin — trusted
            else -> throw CertificateException(
                "TLS certificate for $host does not match the pinned one",
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
