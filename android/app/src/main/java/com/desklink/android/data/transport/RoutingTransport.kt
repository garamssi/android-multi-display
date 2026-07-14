package com.desklink.android.data.transport

import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.TransportMode
import com.desklink.android.domain.transport.Transport
import javax.inject.Inject
import javax.inject.Singleton

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

    private fun strategy(): Transport = when (settings.currentTransportMode()) {
        TransportMode.USB -> usb
        TransportMode.LAN -> lan
    }
}
