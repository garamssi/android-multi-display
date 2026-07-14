package com.desklink.android.data

import com.desklink.android.data.transport.UsbTransport
import com.desklink.android.domain.model.ProtocolConstants
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UsbTransportTest {

    @Test
    fun `usb transport dials loopback`() = runTest {
        assertEquals(ProtocolConstants.LOOPBACK_HOST, UsbTransport().host())
        assertEquals("127.0.0.1", UsbTransport().host())
    }

    @Test
    fun `usb transport uses the plaintext USB port set`() {
        val usb = UsbTransport()
        assertEquals(ProtocolConstants.PORT_CONTROL, usb.controlPort())
        assertEquals(ProtocolConstants.PORT_VIDEO, usb.videoPort())
        assertEquals(ProtocolConstants.PORT_INPUT, usb.inputPort())
    }
}
