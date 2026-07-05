package com.desklink.android.presentation.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.presentation.components.AppGlyph
import com.desklink.android.presentation.components.GhostTextButton
import com.desklink.android.presentation.components.GradientButton
import com.desklink.android.presentation.components.IndeterminateBar
import com.desklink.android.presentation.components.MonoText
import com.desklink.android.presentation.components.OutlineButton
import com.desklink.android.presentation.components.SpinnerRing
import com.desklink.android.presentation.components.StatusDot
import com.desklink.android.presentation.theme.DeskLinkTokens
import com.desklink.android.presentation.theme.PlexSans

@Composable
fun ConnectionScreen(
    onConnected: () -> Unit,
    onSettings: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val usbConnected by viewModel.usbConnected.collectAsStateWithLifecycle()

    // Only navigate to Display in response to a Connect the user initiated here.
    // Without this, returning to this screen while the control channel is still
    // reporting "Connected" (mid-teardown) would immediately bounce back to Display.
    var connectRequested by remember { mutableStateOf(false) }

    // Navigate to the display once fully connected.
    LaunchedEffect(state) {
        if (state is ConnectionState.Connected && connectRequested) {
            connectRequested = false
            onConnected()
        }
    }

    val isBusy = state is ConnectionState.Connecting ||
        state is ConnectionState.Handshaking ||
        state is ConnectionState.Negotiating ||
        state is ConnectionState.Reconnecting

    val isError = state is ConnectionState.Error

    // Page background: the connect flow uses a radial gradient; the error state tints
    // the center red (spec §2/§4). Settings uses a flat bg (that screen owns its own).
    val centerColor =
        if (isError) DeskLinkTokens.PageRadialErrorCenter else DeskLinkTokens.PageRadialCenter

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val pageBrush = Brush.radialGradient(
            colorStops = arrayOf(0f to centerColor, 0.68f to DeskLinkTokens.AppBg),
            center = Offset(wPx * 0.5f, hPx * 0.18f),
            radius = maxOf(wPx, hPx) * 0.9f,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBrush),
        ) {
            when {
                isBusy -> ConnectingContent(
                    modifier = Modifier.align(Alignment.Center),
                    codecLabel = (state as? ConnectionState.Negotiating)
                        ?.config?.codec?.displayLabel() ?: "H.265",
                    onCancel = {
                        connectRequested = false
                        viewModel.disconnect()
                    },
                )

                isError -> ErrorContent(
                    modifier = Modifier.align(Alignment.Center),
                    errorCode = (state as ConnectionState.Error).error.name,
                    onTryAgain = {
                        connectRequested = true
                        viewModel.connect()
                    },
                    onOpenSettings = onSettings,
                )

                else -> {
                    StartContent(
                        modifier = Modifier.align(Alignment.Center),
                        onConnect = {
                            connectRequested = true
                            viewModel.connect()
                        },
                        onSettings = onSettings,
                    )
                    // Bottom USB status pill, reflecting the real USB link state.
                    UsbChip(
                        connected = usbConnected,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 26.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StartContent(
    modifier: Modifier = Modifier,
    onConnect: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppGlyph(size = 78.dp, iconSize = 38.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            text = "DeskLink",
            color = DeskLinkTokens.TextPrimary,
            fontFamily = PlexSans,
            fontSize = 40.sp,
            fontWeight = FontWeight.W700,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Connect to your Mac over USB",
            color = DeskLinkTokens.TextSecondary,
            fontFamily = PlexSans,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(38.dp))
        GradientButton(
            text = "Connect",
            onClick = onConnect,
            leadingIcon = Icons.Outlined.Cable,
            modifier = Modifier.width(230.dp),
            height = 56.dp,
            cornerRadius = DeskLinkTokens.RadiusButtonLarge,
            fontSize = 17.sp,
            iconSize = 19.dp,
            shadowElevation = 22.dp,
        )
        Spacer(Modifier.height(18.dp))
        GhostTextButton(text = "Settings", onClick = onSettings)
    }
}

@Composable
private fun ConnectingContent(
    modifier: Modifier = Modifier,
    codecLabel: String,
    onCancel: () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SpinnerRing(size = 78.dp) {
            AppGlyph(
                size = 56.dp,
                cornerRadius = DeskLinkTokens.RadiusGlyphSmall,
                iconSize = 27.dp,
                elevated = false,
            )
        }
        Spacer(Modifier.height(26.dp))
        Text(
            text = "Connecting…",
            color = DeskLinkTokens.TextPrimary,
            fontFamily = PlexSans,
            fontSize = 24.sp,
            fontWeight = FontWeight.W600,
        )
        Spacer(Modifier.height(22.dp))
        IndeterminateBar()
        Spacer(Modifier.height(18.dp))
        MonoText(
            text = "Negotiating video stream · $codecLabel",
            color = DeskLinkTokens.TextTertiary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(34.dp))
        OutlineButton(
            text = "Cancel",
            onClick = onCancel,
            height = 44.dp,
            cornerRadius = 12.dp,
            fontSize = 15.sp,
            fontWeight = FontWeight.W500,
        )
    }
}

@Composable
private fun ErrorContent(
    modifier: Modifier = Modifier,
    errorCode: String,
    onTryAgain: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Red-tint alert square (78dp).
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(DeskLinkTokens.ShapeGlyph)
                .background(color = DeskLinkTokens.ErrorTintBg, shape = DeskLinkTokens.ShapeGlyph)
                .border(1.dp, DeskLinkTokens.ErrorTintBorder, DeskLinkTokens.ShapeGlyph),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = DeskLinkTokens.ErrorLight,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Connection failed",
            color = DeskLinkTokens.TextPrimary,
            fontFamily = PlexSans,
            fontSize = 26.sp,
            fontWeight = FontWeight.W600,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Couldn't reach the DeskLink server. Make sure the Mac app is " +
                "running and your USB cable is securely connected.",
            color = DeskLinkTokens.TextSecondary,
            fontFamily = PlexSans,
            fontSize = 15.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 400.dp),
        )
        Spacer(Modifier.height(34.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GradientButton(
                text = "Try again",
                onClick = onTryAgain,
                leadingIcon = Icons.Outlined.Refresh,
                height = 52.dp,
                cornerRadius = DeskLinkTokens.RadiusButtonMedium,
                horizontalPadding = 28.dp,
                fontSize = 16.sp,
                iconSize = 17.dp,
            )
            OutlineButton(
                text = "Open Settings",
                onClick = onOpenSettings,
                height = 52.dp,
            )
        }
        Spacer(Modifier.height(26.dp))
        // Error-code chip (mono, red-tinted).
        val chipShape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp)
        Box(
            modifier = Modifier
                .clip(chipShape)
                .background(color = DeskLinkTokens.ErrorChipBg, shape = chipShape)
                .border(1.dp, DeskLinkTokens.ErrorChipBorder, chipShape)
                .padding(horizontal = 11.dp, vertical = 5.dp),
        ) {
            MonoText(
                text = "ERR_$errorCode",
                color = DeskLinkTokens.ErrorChipText,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun UsbChip(connected: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(DeskLinkTokens.ShapePill)
            .background(color = DeskLinkTokens.Surface05, shape = DeskLinkTokens.ShapePill)
            .border(1.dp, DeskLinkTokens.Border08, DeskLinkTokens.ShapePill)
            .padding(horizontal = 15.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Honest cable-level signal: green when a USB data link exists, muted otherwise.
        // It does NOT claim the peer is a Mac — that's only known once Connect succeeds.
        StatusDot(
            color = if (connected) DeskLinkTokens.Success else DeskLinkTokens.TextQuaternary,
            size = 7.dp,
        )
        MonoText(
            text = if (connected) "USB connected" else "No USB",
            color = DeskLinkTokens.TextSecondary,
            fontSize = 12.5.sp,
        )
    }
}

/** Human label for the codec shown in the connecting subtitle. */
private fun DisplayConfig.Codec.displayLabel(): String = when (this) {
    DisplayConfig.Codec.HEVC -> "H.265"
    DisplayConfig.Codec.H264 -> "H.264"
}
