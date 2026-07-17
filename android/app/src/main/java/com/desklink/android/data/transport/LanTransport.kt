package com.desklink.android.data.transport

import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.transport.Transport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LAN transport: dials the Mac's IP (or hostname) — entered manually or picked from
 * discovery — resolved fresh per connect via [SettingsRepository], so editing the
 * address then reconnecting takes effect.
 *
 * Uses the LAN port set (7110-7112), a separate stack from USB so the Mac can serve
 * both at once. This path is TLS + PIN paired (see docs/WIFI_TRANSPORT_DESIGN.md);
 * the pairing key and TLS trust are handled by the connection/security layer, not here.
 */
@Singleton
class LanTransport @Inject constructor(
    private val settings: SettingsRepository,
) : Transport {
    override suspend fun host(): String = settings.currentManualHost()

    override fun controlPort(): Int = ProtocolConstants.PORT_CONTROL_LAN

    override fun videoPort(): Int = ProtocolConstants.PORT_VIDEO_LAN

    override fun inputPort(): Int = ProtocolConstants.PORT_INPUT_LAN
}
