package com.desklink.android.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.desklink.android.presentation.theme.DeskLinkTokens

@Composable
fun StatusDot(
    modifier: Modifier = Modifier,
    color: Color = DeskLinkTokens.Success,
    size: Dp = 7.dp,
    pulsing: Boolean = false,
    glow: Boolean = true,
) {
    val scale: Float
    val alpha: Float
    if (pulsing) {
        val transition = rememberInfiniteTransition(label = "statusDotPulse")
        scale = transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusDotScale",
        ).value
        alpha = transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusDotAlpha",
        ).value
    } else {
        scale = 1f
        alpha = 1f
    }

    Canvas(modifier = modifier.size(size * 2.4f)) {
        val center = androidx.compose.ui.geometry.Offset(this.size.width / 2f, this.size.height / 2f)
        val r = (size.toPx() / 2f) * scale
        if (glow) {
            drawCircle(
                color = color.copy(alpha = 0.55f * alpha),
                radius = r * 2.3f,
                center = center,
            )
        }
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = r,
            center = center,
            style = Fill,
        )
    }
}
