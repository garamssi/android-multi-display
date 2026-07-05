package com.desklink.android.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The fixed dark Material3 [ColorScheme] for DeskLink, mapped onto [DeskLinkTokens].
 *
 * Most of the redesigned UI paints with tokens directly (gradients, translucent
 * surfaces, precise hexes), so this scheme mainly governs the handful of stock M3
 * components still in use and provides sensible fallbacks. There is no dynamic color.
 */
val DeskLinkDarkColorScheme: ColorScheme = darkColorScheme(
    primary = DeskLinkTokens.AccentSolid,
    onPrimary = Color.White,
    primaryContainer = DeskLinkTokens.AccentSelectedBg,
    onPrimaryContainer = DeskLinkTokens.TextPrimary,

    secondary = DeskLinkTokens.AccentLight,
    onSecondary = Color.White,

    tertiary = DeskLinkTokens.AccentViolet,
    onTertiary = Color.White,

    background = DeskLinkTokens.AppBg,
    onBackground = DeskLinkTokens.TextPrimary,

    surface = DeskLinkTokens.AppBg,
    onSurface = DeskLinkTokens.TextPrimary,
    surfaceVariant = Color(0xFF1A1D24),
    onSurfaceVariant = DeskLinkTokens.TextSecondary,

    outline = DeskLinkTokens.Border16,
    outlineVariant = DeskLinkTokens.Border08,

    error = DeskLinkTokens.Error,
    onError = Color.White,
    errorContainer = DeskLinkTokens.ErrorTintBg,
    onErrorContainer = DeskLinkTokens.ErrorText,
)
