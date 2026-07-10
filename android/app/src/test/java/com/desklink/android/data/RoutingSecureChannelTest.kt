package com.desklink.android.data

import com.desklink.android.data.device.ScreenMetricsProvider
import com.desklink.android.data.device.ScreenResolution
import com.desklink.android.data.security.CertPinStore
import com.desklink.android.data.security.PlaintextSecureChannel
import com.desklink.android.data.security.RoutingSecureChannel
import com.desklink.android.data.security.TlsSecureChannel
import com.desklink.android.data.settings.SettingsRepository
import com.desklink.android.domain.model.TransportMode
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.net.Socket

/**
 * USB mode must stay plaintext: RoutingSecureChannel returns the socket unwrapped. (The
 * LAN/TLS branch does a real handshake and is verified on-device, not here.)
 */
class RoutingSecureChannelTest {

    private fun settings(store: FakeSettingsStore) = SettingsRepository(
        object : ScreenMetricsProvider {
            override fun nativeResolution() = ScreenResolution(2560, 1600)
        },
        store,
    )

    @Test
    fun `USB mode returns the socket unwrapped`() {
        val store = FakeSettingsStore()
        val settings = settings(store).apply { setTransportMode(TransportMode.USB) }
        val channel = RoutingSecureChannel(
            settings,
            PlaintextSecureChannel(),
            TlsSecureChannel(CertPinStore(store)),
        )

        val socket = Socket()
        assertSame(socket, channel.secure(socket, "127.0.0.1", 7100))
    }
}
