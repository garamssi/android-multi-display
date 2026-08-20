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

    private companion object {
        const val TAG = "DeskLink"
    }
}
