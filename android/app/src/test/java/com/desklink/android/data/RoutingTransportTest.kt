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

/**
 * The control/video/input channels dial whatever host [RoutingTransport] resolves from
 * the user-selected [TransportMode]: loopback for USB (unchanged), the manually entered
 * IP for LAN. A blank LAN host is surfaced as-is (the connect then fails) rather than
 * silently falling back to USB, so the user is never quietly connected to the wrong
 * target.
 */
class RoutingTransportTest {

    private fun settings() = SettingsRepository(
        object : ScreenMetricsProvider {
            override fun nativeResolution() = ScreenResolution(2560, 1600)
        },
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
}
