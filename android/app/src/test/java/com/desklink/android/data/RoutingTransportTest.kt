package com.desklink.android.data

import com.desklink.android.data.device.ScreenMetricsProvider
import com.desklink.android.data.device.ScreenResolution
import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.data.transport.LanTransport
import com.desklink.android.data.transport.RoutingTransport
import com.desklink.android.data.transport.UsbTransport
import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.model.TransportMode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoutingTransportTest {

    private fun settings() = SettingsRepository(
        object : ScreenMetricsProvider {
            override fun nativeResolution() = ScreenResolution(2560, 1600)
        },
        FakeSettingsStore(),
    )

    private fun routing(settings: SettingsRepository) =
        RoutingTransport(settings, UsbTransport(), LanTransport(settings))

    @Test
    fun `defaults to USB loopback`() = runTest {
        assertEquals(ProtocolConstants.LOOPBACK_HOST, routing(settings()).host())
    }

    @Test
    fun `LAN mode dials the manually entered host`() = runTest {
        val settings = settings()
        settings.setTransportMode(TransportMode.LAN)
        settings.setManualHost("192.168.0.10")

        assertEquals("192.168.0.10", routing(settings).host())
    }

    @Test
    fun `LAN host is trimmed`() = runTest {
        val settings = settings()
        settings.setTransportMode(TransportMode.LAN)
        settings.setManualHost("  192.168.0.42  ")

        assertEquals("192.168.0.42", routing(settings).host())
    }

    @Test
    fun `LAN with no host does not fall back to USB`() = runTest {
        val settings = settings()
        settings.setTransportMode(TransportMode.LAN)

        assertEquals("", routing(settings).host())
    }

    @Test
    fun `switching back to USB restores loopback`() = runTest {
        val settings = settings()
        settings.setTransportMode(TransportMode.LAN)
        settings.setManualHost("10.0.0.5")
        settings.setTransportMode(TransportMode.USB)

        assertEquals(ProtocolConstants.LOOPBACK_HOST, routing(settings).host())
    }

    @Test
    fun `USB mode uses the USB port set`() {
        val routing = routing(settings())
        assertEquals(ProtocolConstants.PORT_CONTROL, routing.controlPort())
        assertEquals(ProtocolConstants.PORT_VIDEO, routing.videoPort())
        assertEquals(ProtocolConstants.PORT_INPUT, routing.inputPort())
    }

    @Test
    fun `LAN mode uses the separate LAN port set`() {
        val settings = settings()
        settings.setTransportMode(TransportMode.LAN)

        val routing = routing(settings)
        assertEquals(ProtocolConstants.PORT_CONTROL_LAN, routing.controlPort())
        assertEquals(ProtocolConstants.PORT_VIDEO_LAN, routing.videoPort())
        assertEquals(ProtocolConstants.PORT_INPUT_LAN, routing.inputPort())
    }
}
