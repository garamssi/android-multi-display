package com.desklink.android.data.security

import com.desklink.android.data.settings.SettingsStore
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trust-on-first-use (TOFU) pin store for the Mac's self-signed TLS certificate,
 * persisted per host via [SettingsStore]. The pin is the SHA-256 of the certificate's
 * DER encoding (hex). First connect to a host records the pin; later connects require
 * the same cert. The PIN pairing step (added next) closes the first-connect gap; this
 * gives encryption + continuity.
 */
@Singleton
class CertPinStore @Inject constructor(
    private val store: SettingsStore,
) {
    fun pinFor(host: String): String? =
        store.getString(keyFor(host), "").ifEmpty { null }

    fun setPin(host: String, fingerprint: String) =
        store.putString(keyFor(host), fingerprint)

    private fun keyFor(host: String) = "$KEY_PREFIX$host"

    companion object {
        private const val KEY_PREFIX = "cert_pin_"

        /** Lowercase hex SHA-256 of a certificate's DER encoding. */
        fun fingerprint(certificateDer: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(certificateDer)
                .joinToString("") { "%02x".format(it) }
    }
}
