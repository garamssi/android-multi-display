package com.desklink.android.presentation.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.desklink.android.presentation.theme.DeskLinkTokens

/**
 * The connecting-screen indeterminate progress bar (handoff `dl-indeterminate`, 1.3s).
 *
 * A 42%-wide accent-gradient fill sweeps across the track from `left:-42%` to
 * `left:100%` on a continuous ease loop, matching the spec keyframes.
 */
@Composable
fun IndeterminateBar(
    modifier: Modifier = Modifier,
    width: Dp = 300.dp,
    height: Dp = 6.dp,
    fillFraction: Float = 0.42f,
) {
    val trackShape = DeskLinkTokens.ShapePill
    BoxWithConstraints(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(trackShape)
            .background(color = DeskLinkTokens.Border08, shape = trackShape),
    ) {
        val trackWidthPx = constraints.maxWidth.toFloat()
        val transition = rememberInfiniteTransition(label = "indeterminate")
        // Fraction of the track width: -0.42 (fully off left) → 1.0 (fully off right).
        val progress by transition.animateFloat(
            initialValue = -fillFraction,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1300, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
                repeatMode = RepeatMode.Restart,
            ),
            label = "indeterminateProgress",
        )
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth(fillFraction)
                .fillMaxHeight()
                .offset { androidx.compose.ui.unit.IntOffset((progress * trackWidthPx).toInt(), 0) }
                .clip(trackShape)
                .background(brush = DeskLinkTokens.ProgressGradient, shape = trackShape),
        )
    }
}
