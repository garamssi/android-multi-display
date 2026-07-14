package com.desklink.android.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.desklink.android.presentation.theme.DeskLinkTokens

@Composable
fun AppGlyph(
    modifier: Modifier = Modifier,
    size: Dp = 78.dp,
    cornerRadius: Dp = DeskLinkTokens.RadiusGlyph,
    iconSize: Dp = 38.dp,
    elevated: Boolean = true,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (elevated) {
                    Modifier.shadow(
                        elevation = 22.dp,
                        shape = shape,
                        ambientColor = DeskLinkTokens.AccentSolid,
                        spotColor = DeskLinkTokens.AccentSolid,
                    )
                } else {
                    Modifier
                },
            )
            .background(brush = DeskLinkTokens.AppGlyphGradient, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.DesktopWindows,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}
