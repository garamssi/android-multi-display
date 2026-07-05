package com.desklink.android.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * DeskLink's app theme: a FIXED dark "pro tool" (Linear/Raycast direction) theme.
 *
 * There is intentionally no light variant and no Material You / dynamic color — the
 * hi-fi handoff specifies exact dark tokens. IBM Plex Sans/Mono typography is applied
 * globally; the color scheme is mapped from [DeskLinkTokens] (see [DeskLinkDarkColorScheme]).
 */
@Composable
fun DeskLinkTheme(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            // Keep the transparent, edge-to-edge system bars set up by enableEdgeToEdge()
            // and the platform theme; only force LIGHT bar icons so they stay legible
            // over the permanently-dark background regardless of the device's day/night
            // setting.
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = DeskLinkDarkColorScheme,
        typography = DeskLinkTypography,
        content = content,
    )
}
