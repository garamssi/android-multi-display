package com.desklink.android.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.desklink.android.presentation.theme.DeskLinkTokens

/**
 * A 48dp circular "glass" overlay button used by the in-stream floating control.
 *
 * Compose can't reproduce CSS `backdrop-filter: blur()`, so the frosted look is
 * approximated with a near-opaque dark fill ([DeskLinkTokens.GlassFill]) plus a hairline
 * border and a soft drop shadow — visually equivalent over the mirrored desktop.
 */
@Composable
fun GlassCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 21.dp,
    containerColor: Color = DeskLinkTokens.GlassFill,
    borderColor: Color = DeskLinkTokens.Border16,
    iconTint: Color = DeskLinkTokens.TextPrimary,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .shadow(elevation = 12.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(color = containerColor, shape = CircleShape)
            .border(BorderStroke(1.dp, borderColor), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(iconSize),
        )
    }
}
