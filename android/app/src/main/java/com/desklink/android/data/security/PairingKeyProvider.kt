package com.desklink.android.data.security

import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.TransportMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the pairing key (HKDF of the PIN, see [PairingCrypto]) for the current
 * connection, or null when no authentication applies. Null means "skip the LAN auth
 * handshake": USB (loopback) is trusted by its physical link.
 */
interface PairingKeyProvider {
    fun currentKey(): ByteArray?
}

/** LAN uses the key derived from the entered PIN; USB returns null (no auth). */
@Singleton
class SettingsPairingKeyProvider @Inject constructor(
    private val settings: SettingsRepository,
) : PairingKeyProvider {
    override fun currentKey(): ByteArray? =
        if (settings.currentTransportMode() == TransportMode.LAN) {
            PairingCrypto.derivePsk(settings.currentPairingPin())
        } else {
            null
        }
}
