package com.desklink.android.data.transport

import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.transport.Transport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LAN transport: dials the Mac's IP (or hostname) that the user entered manually in
 * Settings (no discovery in this phase). Resolved fresh per connect via
 * [SettingsRepository], so editing the address then reconnecting takes effect.
 *
 * Plaintext and unauthenticated for now — this path is development-only and gated
 * behind an explicit opt-in in the UI. TLS + pairing land in a later phase (see
 * docs/WIFI_TRANSPORT_DESIGN.md).
 */
@Singleton
class LanTransport @Inject constructor(
    private val settings: SettingsRepository,
) : Transport {
    override suspend fun host(): String = settings.currentManualHost()
}
