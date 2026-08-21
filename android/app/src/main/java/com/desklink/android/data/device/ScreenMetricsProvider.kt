package com.desklink.android.data.device

data class ScreenResolution(val width: Int, val height: Int)

interface ScreenMetricsProvider {
    fun nativeResolution(): ScreenResolution

    // Highest frame rate this panel can actually present, so the handshake advertises the
    // device's real capability instead of a fixed figure. Claiming more makes the Mac
    // build a virtual display and encode frames the panel can never show.
    fun maxRefreshRate(): Int
}
