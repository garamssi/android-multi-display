package com.desklink.android.data.transport

import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.transport.Transport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * USB transport: the client dials the device's loopback, which `adb reverse` tunnels
 * over USB to the Mac server. The host is therefore the fixed loopback address; the
 * reverse tunnel itself is set up on the Mac side.
 */
@Singleton
class UsbTransport @Inject constructor() : Transport {
    override suspend fun host(): String = ProtocolConstants.LOOPBACK_HOST
}
