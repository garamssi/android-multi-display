package com.desklink.android.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.desklink.android.presentation.theme.DeskLinkTokens
import com.desklink.android.presentation.theme.PlexSans

/**
 * A single resolution preset card (radio dot + name + mono resolution value).
 *
 * Selected: border `rgba(124,134,255,.5)`, bg `rgba(124,134,255,.1)`, filled radio.
 * Unselected: border `rgba(255,255,255,.08)`, bg `rgba(255,255,255,.03)`, hollow radio.
 */
@Composable
fun ResolutionRadioCard(
    name: String,
    value: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) DeskLinkTokens.AccentSelectedBorder else DeskLinkTokens.Border08
    val bgColor = if (selected) DeskLinkTokens.AccentSelectedBg else DeskLinkTokens.Surface03
    val valueColor = if (selected) DeskLinkTokens.TextSecondary else DeskLinkTokens.TextTertiary

    Row(
        modifier = modifier
            .clip(DeskLinkTokens.ShapeCard)
            .background(color = bgColor, shape = DeskLinkTokens.ShapeCard)
            .border(BorderStroke(1.dp, borderColor), DeskLinkTokens.ShapeCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        RadioDot(selected = selected)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = name,
                color = DeskLinkTokens.TextPrimary,
                fontFamily = PlexSans,
                fontSize = 15.sp,
                fontWeight = FontWeight.W600,
            )
            MonoText(
                text = value,
                color = valueColor,
                fontSize = 12.5.sp,
            )
        }
    }
}

/** A 20dp radio indicator: 2dp ring, filled 9dp center when selected. */
@Composable
private fun RadioDot(selected: Boolean, modifier: Modifier = Modifier) {
    val ringColor = if (selected) DeskLinkTokens.AccentLight else DeskLinkTokens.Border16.copy(alpha = 0.22f)
    Box(
        modifier = modifier
            .size(20.dp)
            .border(BorderStroke(2.dp, ringColor), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(color = DeskLinkTokens.AccentLight, shape = CircleShape),
            )
        }
    }
}
