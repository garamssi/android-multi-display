package com.desklink.android.data.security

import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.TransportMode
import javax.inject.Inject
import javax.inject.Singleton

interface PairingKeyProvider {
    fun currentKey(): ByteArray?
}

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
