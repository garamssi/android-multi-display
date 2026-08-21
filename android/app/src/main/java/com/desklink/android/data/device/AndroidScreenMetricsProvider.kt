package com.desklink.android.data.device

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidScreenMetricsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ScreenMetricsProvider {

    // The PANEL's resolution, which is what the Mac has to encode for -- not the window's.
    // `currentWindowMetrics.bounds` reports the current window, so in split screen it would
    // call half the panel "native" and the Mac would stream at half size for the rest of the
    // session. Display.Mode carries the panel's physical size and is unaffected by window
    // state or rotation.
    override fun nativeResolution(): ScreenResolution {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val mode = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
        val physical = mode?.let { it.physicalWidth to it.physicalHeight }

        val (px, py) = physical?.takeIf { it.first > 0 && it.second > 0 } ?: windowFallback()
        Log.i(TAG, "native screen metrics: ${px}x$py")
        return ScreenResolution(px, py)
    }

    // Only for a platform that reports no usable mode; the window is the best guess left.
    private fun windowFallback(): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val point = Point().also { windowManager.defaultDisplay.getRealSize(it) }
            point.x to point.y
        }
    }

    override fun maxRefreshRate(): Int {
        // Queried through DisplayManager, NOT Context.getDisplay(): this provider is
        // constructed with the application context, and getDisplay() throws
        // UnsupportedOperationException on any context that is not visual (an Activity or
        // a window/display context). DisplayManager works from any context.
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)

        // Take the best of the supported modes, not the mode that happens to be active:
        // a panel often idles at 60 Hz and switches up under load, and the handshake is
        // advertising what the device is capable of.
        val fromModes = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f
        val rate = maxOf(fromModes, display?.refreshRate ?: 0f)
        val rounded = rate.toInt()
        Log.i(TAG, "native refresh rate: $rounded Hz")
        return if (rounded >= MIN_REFRESH_RATE) rounded else FALLBACK_REFRESH_RATE
    }

    private companion object {
        const val TAG = "DeskLink"

        // Below this the reading is not credible (a stub display, or an API returning 0).
        const val MIN_REFRESH_RATE = 20

        // Used only when the platform reports nothing usable.
        const val FALLBACK_REFRESH_RATE = 60
    }
}
