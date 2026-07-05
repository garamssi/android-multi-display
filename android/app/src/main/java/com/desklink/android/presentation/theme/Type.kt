package com.desklink.android.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.desklink.android.R

/**
 * IBM Plex Sans — the primary UI typeface (weights 400/500/600/700). Static ttf
 * assets live in `res/font`.
 */
val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.W400),
    Font(R.font.ibm_plex_sans_medium, FontWeight.W500),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.W600),
    Font(R.font.ibm_plex_sans_bold, FontWeight.W700),
)

/**
 * IBM Plex Mono — the technical/values typeface (weights 400/500/600). Used for
 * resolutions, fps, bitrate, timers, status chips, error codes and section labels.
 */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.W400),
    Font(R.font.ibm_plex_mono_medium, FontWeight.W500),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.W600),
)

/**
 * Material3 typography, entirely re-based on IBM Plex Sans so any stock Material
 * component picks up the correct family. Screen-specific sizes are applied inline
 * (the handoff uses a bespoke type scale that doesn't map cleanly onto the M3 roles).
 */
val DeskLinkTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = PlexSans),
        displayMedium = displayMedium.copy(fontFamily = PlexSans),
        displaySmall = displaySmall.copy(fontFamily = PlexSans),
        headlineLarge = headlineLarge.copy(fontFamily = PlexSans, fontWeight = FontWeight.W700),
        headlineMedium = headlineMedium.copy(fontFamily = PlexSans, fontWeight = FontWeight.W600),
        headlineSmall = headlineSmall.copy(fontFamily = PlexSans, fontWeight = FontWeight.W600),
        titleLarge = titleLarge.copy(fontFamily = PlexSans, fontWeight = FontWeight.W600),
        titleMedium = titleMedium.copy(fontFamily = PlexSans, fontWeight = FontWeight.W600),
        titleSmall = titleSmall.copy(fontFamily = PlexSans, fontWeight = FontWeight.W500),
        bodyLarge = bodyLarge.copy(fontFamily = PlexSans),
        bodyMedium = bodyMedium.copy(fontFamily = PlexSans),
        bodySmall = bodySmall.copy(fontFamily = PlexSans),
        labelLarge = labelLarge.copy(fontFamily = PlexSans, fontWeight = FontWeight.W500),
        labelMedium = labelMedium.copy(fontFamily = PlexSans, fontWeight = FontWeight.W500),
        labelSmall = labelSmall.copy(fontFamily = PlexSans, fontWeight = FontWeight.W500),
    )
}
