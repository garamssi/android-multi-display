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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.desklink.android.presentation.theme.DeskLinkTokens

/**
 * Generic single-choice segmented control matching the handoff spec:
 * container `rgba(255,255,255,.05)` bg + `rgba(255,255,255,.07)` border, 4dp padding,
 * 4dp gap between segments; each segment `flex:1`, 10dp radius; the selected segment
 * gets the accent gradient + colored shadow, while unselected segments are transparent.
 *
 * [content] fully owns each segment's interior (it receives the option + whether it's
 * selected), so callers can render single-line labels (with a leading check) or the
 * two-line bitrate labels.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 46.dp,
    content: @Composable (option: T, isSelected: Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(DeskLinkTokens.ShapeSegmentTrack)
            .background(color = DeskLinkTokens.Surface05, shape = DeskLinkTokens.ShapeSegmentTrack)
            .border(BorderStroke(1.dp, DeskLinkTokens.Border07), DeskLinkTokens.ShapeSegmentTrack)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val base = Modifier
                .weight(1f)
                .height(itemHeight)
            val styled = if (isSelected) {
                base
                    .shadow(
                        elevation = 10.dp,
                        shape = DeskLinkTokens.ShapeSegment,
                        ambientColor = DeskLinkTokens.AccentSolid,
                        spotColor = DeskLinkTokens.AccentSolid,
                    )
                    .clip(DeskLinkTokens.ShapeSegment)
                    .background(brush = DeskLinkTokens.AccentVertical, shape = DeskLinkTokens.ShapeSegment)
            } else {
                base.clip(DeskLinkTokens.ShapeSegment)
            }
            Box(
                modifier = styled.clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                content(option, isSelected)
            }
        }
    }
}
