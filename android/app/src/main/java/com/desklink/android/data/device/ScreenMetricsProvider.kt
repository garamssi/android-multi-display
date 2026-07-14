package com.desklink.android.data.device

data class ScreenResolution(val width: Int, val height: Int)

interface ScreenMetricsProvider {
    fun nativeResolution(): ScreenResolution
}
