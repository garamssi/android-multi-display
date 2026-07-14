package com.desklink.android.data.transport

import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.transport.Transport
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanTransport @Inject constructor(
    private val settings: SettingsRepository,
) : Transport {
    override suspend fun host(): String = settings.currentManualHost()

    override fun controlPort(): Int = ProtocolConstants.PORT_CONTROL_LAN

    override fun videoPort(): Int = ProtocolConstants.PORT_VIDEO_LAN

    override fun inputPort(): Int = ProtocolConstants.PORT_INPUT_LAN
}
