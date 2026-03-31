package com.desklink.android.presentation.display

import android.app.Activity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
fun DisplayScreen(
    onDisconnected: () -> Unit,
) {
    val context = LocalContext.current

    // Immersive fullscreen mode
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.let {
            val windowInsetsController = WindowCompat.getInsetsController(it.window, it.window.decorView)
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        onDispose {
            activity?.let {
                val windowInsetsController = WindowCompat.getInsetsController(it.window, it.window.decorView)
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // Surface ready — decoder will be configured with this surface
                            // Choreographer vsync callback will drive renderFrame()
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            // Resolution changed — may need decoder reconfiguration
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            // Release decoder when surface is destroyed
                        }
                    })
                    keepScreenOn = true
                }
            },
        )
    }
}
