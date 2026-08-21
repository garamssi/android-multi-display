package com.desklink.android.data.device

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.util.Log
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidScreenMetricsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ScreenMetricsProvider {

    override fun nativeResolution(): ScreenResolution {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (px, py) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val point = Point().also { windowManager.defaultDisplay.getRealSize(it) }
            point.x to point.y
        }
        Log.i(TAG, "native screen metrics: ${px}x$py")
        return ScreenResolution(px, py)
    }

    override fun maxRefreshRate(): Int {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        }
        // Take the best of the supported modes, not the mode that happens to be active:
        // a panel often idles at 60 Hz and switches up under load, and the handshake is
        // advertising what the device is capable of.
        val fromModes = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f
        val rate = maxOf(fromModes, display?.refreshRate ?: 0f)
        val rounded = rate.toInt()
        Log.i(TAG, "native refresh rate: ${rounded} Hz")
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
