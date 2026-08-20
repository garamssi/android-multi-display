package com.desklink.android.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.desklink.android.presentation.theme.DeskLinkTokens
import com.desklink.android.presentation.theme.PlexSans

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
    height: Dp = 40.dp,
    cornerRadius: Dp = DeskLinkTokens.RadiusButton,
    horizontalPadding: Dp = 20.dp,
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight = FontWeight.W600,
    iconSize: Dp = 18.dp,
    shadowElevation: Dp = 14.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .alpha(if (enabled) 1f else 0.5f)
            .shadow(
                elevation = shadowElevation,
                shape = shape,
                ambientColor = DeskLinkTokens.AccentSolid,
                spotColor = DeskLinkTokens.AccentSolid,
            )
            .clip(shape)
            .background(brush = DeskLinkTokens.AccentVertical, shape = shape)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(iconSize),
                )
            }
            Text(
                text = text,
                color = Color.White,
                fontFamily = PlexSans,
                fontSize = fontSize,
                fontWeight = fontWeight,
            )
        }
    }
}

@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    cornerRadius: Dp = DeskLinkTokens.RadiusButtonMedium,
    horizontalPadding: Dp = 26.dp,
    fontSize: TextUnit = 16.sp,
    fontWeight: FontWeight = FontWeight.W600,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .border(BorderStroke(1.dp, DeskLinkTokens.Border14), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = DeskLinkTokens.TextBody,
            fontFamily = PlexSans,
            fontSize = fontSize,
            fontWeight = fontWeight,
        )
    }
}

@Composable
fun GhostTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = DeskLinkTokens.TextSecondary,
    fontSize: TextUnit = 15.sp,
    fontWeight: FontWeight = FontWeight.W500,
) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            fontFamily = PlexSans,
            fontSize = fontSize,
            fontWeight = fontWeight,
        )
    }
}
