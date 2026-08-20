package com.desklink.android.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

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
