package com.desklink.android.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.desklink.android.R

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.W400),
    Font(R.font.ibm_plex_sans_medium, FontWeight.W500),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.W600),
    Font(R.font.ibm_plex_sans_bold, FontWeight.W700),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.W400),
    Font(R.font.ibm_plex_mono_medium, FontWeight.W500),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.W600),
)

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
