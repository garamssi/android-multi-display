package com.desklink.android.presentation.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.desklink.android.presentation.theme.PlexMono

/**
 * IBM Plex Mono text — the project's canonical way to render technical values,
 * timers, resolutions, error codes and uppercase section labels. Wraps [Text] with
 * the mono family and CSS-parity `letter-spacing` (expressed in `em`).
 */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = 12.5.sp,
    fontWeight: FontWeight = FontWeight.W400,
    letterSpacingEm: Float = 0f,
    uppercase: Boolean = false,
    textAlign: TextAlign? = null,
) {
    Text(
        text = if (uppercase) text.uppercase() else text,
        modifier = modifier,
        color = color,
        fontFamily = PlexMono,
        fontSize = fontSize,
        fontWeight = fontWeight,
        letterSpacing = letterSpacingEm.em,
        textAlign = textAlign,
    )
}
