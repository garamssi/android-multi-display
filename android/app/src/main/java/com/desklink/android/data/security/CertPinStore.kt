package com.desklink.android.data.security

import com.desklink.android.data.settings.SettingsStore
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

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

        fun fingerprint(certificateDer: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(certificateDer)
                .joinToString("") { "%02x".format(it) }
    }
}
