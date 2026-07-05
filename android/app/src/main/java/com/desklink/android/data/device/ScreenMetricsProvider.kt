package com.desklink.android.data.device

/** The device's real native display size in pixels, as reported by the platform. */
data class ScreenResolution(val width: Int, val height: Int)

/**
 * Detects the device's real, full-pixel native screen size.
 *
 * Abstracted behind an interface so the value can be injected/faked in unit tests:
 * the Android implementation needs a `WindowManager`, which isn't available on the
 * plain JVM used by unit tests.
 */
interface ScreenMetricsProvider {
    fun nativeResolution(): ScreenResolution
}
