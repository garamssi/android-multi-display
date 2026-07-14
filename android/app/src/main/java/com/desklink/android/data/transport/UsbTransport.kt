package com.desklink.android.data.transport

import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.transport.Transport
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbTransport @Inject constructor() : Transport {
    override suspend fun host(): String = ProtocolConstants.LOOPBACK_HOST

    override fun controlPort(): Int = ProtocolConstants.PORT_CONTROL

    override fun videoPort(): Int = ProtocolConstants.PORT_VIDEO

    override fun inputPort(): Int = ProtocolConstants.PORT_INPUT
}
