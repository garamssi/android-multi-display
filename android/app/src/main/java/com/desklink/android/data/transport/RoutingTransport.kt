package com.desklink.android.data.transport

import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.TransportMode
import com.desklink.android.domain.transport.Transport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single [Transport] the control/video/input channels depend on. It resolves the
 * dial host from the user-selected [TransportMode] at connect time (read per connect),
 * delegating to the matching strategy: [UsbTransport] for USB, [LanTransport] for LAN.
 * So switching mode in Settings and reconnecting picks up the new target with no change
 * to the channels.
 *
 * USB keeps its exact prior behavior (loopback). A later phase (P4) extends this into
 * an auto-select/fallback policy; today it is a direct mapping of the user's explicit
 * choice.
 */
@Singleton
class RoutingTransport @Inject constructor(
    private val settings: SettingsRepository,
    private val usb: UsbTransport,
    private val lan: LanTransport,
) : Transport {
    override suspend fun host(): String = strategy().host()

    override fun controlPort(): Int = strategy().controlPort()

    override fun videoPort(): Int = strategy().videoPort()

    override fun inputPort(): Int = strategy().inputPort()

    /** The transport matching the user's current mode, read per call so host and ports
     *  always come from the same strategy for a given connect. */
    private fun strategy(): Transport = when (settings.currentTransportMode()) {
        TransportMode.USB -> usb
        TransportMode.LAN -> lan
    }
}
