package com.desklink.android.presentation.connection

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.desklink.android.domain.model.ConnectionError
import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.TransportMode
import com.desklink.android.domain.transport.DiscoveredServer
import com.desklink.android.presentation.components.AppGlyph
import com.desklink.android.presentation.components.GradientButton
import com.desklink.android.presentation.components.IndeterminateBar
import com.desklink.android.presentation.components.MonoText
import com.desklink.android.presentation.components.OutlineButton
import com.desklink.android.presentation.components.SpinnerRing
import com.desklink.android.presentation.components.StatusDot
import com.desklink.android.presentation.theme.DeskLinkTokens
import com.desklink.android.presentation.theme.PlexSans

/** A Wi-Fi pairing target: a discovered [server] (name + host), or a manual IP (server = null). */
private data class PairingTarget(val name: String, val host: String, val server: DiscoveredServer?)

@Composable
fun ConnectionScreen(
    onConnected: () -> Unit,
    onSettings: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val usbConnected by viewModel.usbConnected.collectAsStateWithLifecycle()
    val transportMode by viewModel.transportMode.collectAsStateWithLifecycle()
    val discoveredServers by viewModel.discoveredServers.collectAsStateWithLifecycle()
    val lastConnectedHost by viewModel.lastConnectedHost.collectAsStateWithLifecycle()

    // Only navigate to Display in response to a Connect the user initiated here.
    var connectRequested by remember { mutableStateOf(false) }

    // Wi-Fi pairing: the target being paired (a tapped server, or a manual IP), plus the
    // PIN screen's phase/attempt count. All reset when the target changes.
    var pairingTarget by remember { mutableStateOf<PairingTarget?>(null) }
    var showEnterIp by remember { mutableStateOf(false) }
    var pairingPhase by remember(pairingTarget) { mutableStateOf(PairingPhase.Entering) }
    var pairingAttempts by remember(pairingTarget) { mutableStateOf(0) }
    var pairingSubmitted by remember(pairingTarget) { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state is ConnectionState.Connected && connectRequested) {
            connectRequested = false
            pairingTarget = null
            onConnected()
        }
    }

    // Drive the PIN screen from the connection result once the user has submitted a PIN:
    // a wrong PIN shows the retry state; any other failure leaves pairing for the home error.
    LaunchedEffect(state, pairingTarget) {
        if (pairingTarget == null || !pairingSubmitted) return@LaunchedEffect
        val current = state
        if (current is ConnectionState.Error) {
            if (current.error == ConnectionError.PAIRING_REJECTED) {
                pairingAttempts += 1
                pairingPhase = PairingPhase.WrongPin
            } else {
                pairingTarget = null
            }
        }
    }

    val isBusy = state is ConnectionState.Connecting ||
        state is ConnectionState.Handshaking ||
        state is ConnectionState.Negotiating ||
        state is ConnectionState.Reconnecting
    val isError = state is ConnectionState.Error

    val pairingTargetNow = pairingTarget

    val centerColor =
        if (isError && pairingTargetNow == null) DeskLinkTokens.PageRadialErrorCenter
        else DeskLinkTokens.PageRadialCenter

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
                pairingTargetNow != null -> PinEntryContent(
                    deviceName = pairingTargetNow.name,
                    host = pairingTargetNow.host,
                    phase = pairingPhase,
                    attemptsUsed = pairingAttempts,
                    onSubmit = { pin ->
                        pairingSubmitted = true
                        pairingPhase = PairingPhase.Verifying
                        connectRequested = true
                        val server = pairingTargetNow.server
                        if (server != null) viewModel.connectTo(server, pin)
                        else viewModel.connectToManual(pairingTargetNow.host, pin)
                    },
                    onTryAgain = {
                        pairingSubmitted = false
                        pairingPhase = PairingPhase.Entering
                    },
                    onBack = {
                        connectRequested = false
                        viewModel.disconnect()
                        pairingTarget = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )

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
                    transportMode = transportMode,
                    onTryAgain = {
                        connectRequested = true
                        viewModel.connect()
                    },
                    onOpenSettings = onSettings,
                )

                transportMode == TransportMode.LAN -> WifiDiscoveryContent(
                    modifier = Modifier.fillMaxSize(),
                    servers = discoveredServers,
                    lastConnectedHost = lastConnectedHost,
                    onStartDiscovery = viewModel::startDiscovery,
                    onStopDiscovery = viewModel::stopDiscovery,
                    onSelectServer = { pairingTarget = PairingTarget(it.name, it.host, it) },
                    onEnterIp = { showEnterIp = true },
                )

                else -> {
                    StartContent(
                        modifier = Modifier.align(Alignment.Center),
                        onConnect = {
                            connectRequested = true
                            viewModel.connect()
                        },
                    )
                    UsbChip(
                        connected = usbConnected,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 26.dp),
                    )
                }
            }

            // Top chrome (brand + mode pill + gear) on the home states only.
            if (!isBusy && !isError && pairingTargetNow == null) {
                HomeChrome(
                    transportMode = transportMode,
                    onSettings = onSettings,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding(),
                )
            }
        }

        if (showEnterIp) {
            EnterIpDialog(
                onContinue = { host ->
                    pairingTarget = PairingTarget(name = host, host = host, server = null)
                    showEnterIp = false
                },
                onDismiss = { showEnterIp = false },
            )
        }
    }
}

/** Top bar: brand mark + transport-mode pill (left), settings gear (right). */
@Composable
private fun HomeChrome(
    transportMode: TransportMode,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppGlyph(size = 26.dp, cornerRadius = 8.dp, iconSize = 14.dp, elevated = false)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "DeskLink",
            color = DeskLinkTokens.TextPrimary,
            fontFamily = PlexSans,
            fontSize = 15.sp,
            fontWeight = FontWeight.W600,
        )
        Spacer(Modifier.width(10.dp))
        ModePill(transportMode)
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(DeskLinkTokens.ShapeChip)
                .background(DeskLinkTokens.Surface04, DeskLinkTokens.ShapeChip)
                .border(BorderStroke(1.dp, DeskLinkTokens.Border10), DeskLinkTokens.ShapeChip)
                .clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Settings",
                tint = DeskLinkTokens.TextValue,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ModePill(transportMode: TransportMode) {
    val isWifi = transportMode == TransportMode.LAN
    Row(
        modifier = Modifier
            .clip(DeskLinkTokens.ShapePill)
            .background(DeskLinkTokens.AccentSelectedBg, DeskLinkTokens.ShapePill)
            .border(BorderStroke(1.dp, DeskLinkTokens.AccentSelectedBorder), DeskLinkTokens.ShapePill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (isWifi) Icons.Outlined.Wifi else Icons.Outlined.Cable,
            contentDescription = null,
            tint = DeskLinkTokens.AccentLight,
            modifier = Modifier.size(12.dp),
        )
        MonoText(
            text = if (isWifi) "Wi-Fi" else "USB",
            color = DeskLinkTokens.AccentLight,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun StartContent(
    modifier: Modifier = Modifier,
    onConnect: () -> Unit,
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
    }
}

/**
 * Wi-Fi home: browse Macs advertised on the LAN and tap one to pair + connect. Handles
 * the NEARBY_WIFI_DEVICES permission (Android 13+); scans while shown, stops on leave.
 */
@Composable
private fun WifiDiscoveryContent(
    modifier: Modifier = Modifier,
    servers: List<DiscoveredServer>,
    lastConnectedHost: String,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onSelectServer: (DiscoveredServer) -> Unit,
    onEnterIp: () -> Unit,
) {
    val context = LocalContext.current
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var permissionGranted by remember {
        mutableStateOf(
            !needsPermission || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    DisposableEffect(permissionGranted) {
        if (permissionGranted) onStartDiscovery()
        onDispose { onStopDiscovery() }
    }

    val found = servers.isNotEmpty()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(96.dp))
        if (found) {
            AppGlyph(size = 72.dp, iconSize = 34.dp)
        } else {
            RadarHero()
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "Choose your Device",
            color = DeskLinkTokens.TextPrimary,
            fontFamily = PlexSans,
            fontSize = 28.sp,
            fontWeight = FontWeight.W700,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                !permissionGranted -> "Allow nearby-devices access to find Macs on Wi-Fi"
                found -> "${servers.size} server${if (servers.size == 1) "" else "s"} found on your Wi-Fi network"
                else -> "Looking for DeskLink servers on your Wi-Fi network"
            },
            color = DeskLinkTokens.TextSecondary,
            fontFamily = PlexSans,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(30.dp))

        Column(modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
            when {
                !permissionGranted -> GradientButton(
                    text = "Find Macs",
                    onClick = { permissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES) },
                    fillWidth = true,
                    height = 52.dp,
                    cornerRadius = DeskLinkTokens.RadiusButtonLarge,
                    fontSize = 16.sp,
                )

                found -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MonoText(
                            text = "AVAILABLE",
                            color = DeskLinkTokens.TextQuaternary,
                            fontSize = 11.sp,
                            letterSpacingEm = 0.16f,
                            uppercase = true,
                        )
                        Spacer(Modifier.weight(1f))
                        SpinnerRing(size = 13.dp, strokeWidth = 2.dp) {}
                    }
                    Spacer(Modifier.height(10.dp))
                    servers.forEach { server ->
                        DiscoveredServerCard(
                            server = server,
                            isRecent = server.host == lastConnectedHost && lastConnectedHost.isNotEmpty(),
                            onClick = { onSelectServer(server) },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }

                else -> ShimmerSkeletonCard()
            }
        }

        Spacer(Modifier.height(24.dp))
        OutlineButton(
            text = "Enter IP manually",
            onClick = onEnterIp,
            height = 46.dp,
            cornerRadius = 13.dp,
            fontSize = 14.5.sp,
        )
        Spacer(Modifier.height(40.dp))
    }
}

/** App glyph with two expanding radar rings (searching state). */
@Composable
private fun RadarHero() {
    val transition = rememberInfiniteTransition(label = "radar")
    Box(
        modifier = Modifier.size(150.dp),
        contentAlignment = Alignment.Center,
    ) {
        listOf(0, 1300).forEach { delayMs ->
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2600, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(delayMs),
                ),
                label = "ring$delayMs",
            )
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .scale(0.55f + progress * 1.55f)
                    .alpha((1f - progress) * 0.55f)
                    .clip(DeskLinkTokens.ShapePill)
                    .border(1.5.dp, DeskLinkTokens.AccentSelectedBorder, DeskLinkTokens.ShapePill),
            )
        }
        AppGlyph(size = 82.dp, iconSize = 40.dp)
    }
}

/** Shimmering placeholder card shown while scanning. */
@Composable
private fun ShimmerSkeletonCard() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DeskLinkTokens.ShapeCard)
            .border(BorderStroke(1.dp, DeskLinkTokens.Border06), DeskLinkTokens.ShapeCard)
            .background(DeskLinkTokens.Surface03, DeskLinkTokens.ShapeCard)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .alpha(pulse)
                .clip(DeskLinkTokens.ShapeChip)
                .background(DeskLinkTokens.Surface09),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.52f)
                    .height(13.dp)
                    .alpha(pulse)
                    .clip(DeskLinkTokens.ShapeChip)
                    .background(DeskLinkTokens.Surface09),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.32f)
                    .height(11.dp)
                    .alpha(pulse * 0.8f)
                    .clip(DeskLinkTokens.ShapeChip)
                    .background(DeskLinkTokens.Surface06),
            )
        }
        SpinnerRing(size = 20.dp, strokeWidth = 2.dp) {}
    }
}

@Composable
private fun DiscoveredServerCard(
    server: DiscoveredServer,
    isRecent: Boolean,
    onClick: () -> Unit,
) {
    val border = if (isRecent) DeskLinkTokens.AccentSelectedBorder else DeskLinkTokens.Border08
    val fill = if (isRecent) DeskLinkTokens.AccentSelectedBg else DeskLinkTokens.Surface03
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DeskLinkTokens.ShapeCard)
            .background(fill, DeskLinkTokens.ShapeCard)
            .border(BorderStroke(1.dp, border), DeskLinkTokens.ShapeCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(DeskLinkTokens.ShapeChip)
                .background(DeskLinkTokens.AccentSelectedBg, DeskLinkTokens.ShapeChip)
                .border(BorderStroke(1.dp, DeskLinkTokens.AccentSelectedBorder), DeskLinkTokens.ShapeChip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Cable,
                contentDescription = null,
                tint = DeskLinkTokens.AccentLight,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    text = server.name,
                    color = DeskLinkTokens.TextPrimary,
                    fontFamily = PlexSans,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.W600,
                )
                if (isRecent) RecentPill()
            }
            Spacer(Modifier.height(4.dp))
            MonoText(
                text = server.osVersion?.let { "${server.host} · $it" } ?: server.host,
                color = DeskLinkTokens.TextSecondary,
                fontSize = 13.sp,
            )
        }
        if (isRecent) {
            Row(
                modifier = Modifier
                    .clip(DeskLinkTokens.ShapeChip)
                    .background(DeskLinkTokens.AccentVertical, DeskLinkTokens.ShapeChip)
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Connect",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontFamily = PlexSans,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.W600,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(DeskLinkTokens.ShapeChip)
                    .border(BorderStroke(1.dp, DeskLinkTokens.Border10), DeskLinkTokens.ShapeChip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Cable,
                    contentDescription = "Connect",
                    tint = DeskLinkTokens.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun RecentPill() {
    Box(
        modifier = Modifier
            .clip(DeskLinkTokens.ShapePill)
            .background(DeskLinkTokens.SuccessChipBg, DeskLinkTokens.ShapePill)
            .border(BorderStroke(1.dp, DeskLinkTokens.SuccessChipBorder), DeskLinkTokens.ShapePill)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        MonoText(
            text = "RECENT",
            color = DeskLinkTokens.SuccessText,
            fontSize = 10.sp,
            letterSpacingEm = 0.05f,
            uppercase = true,
        )
    }
}

/**
 * Manual-IP entry: collects the Mac's address, then hands off to the PIN screen. The PIN
 * itself is entered on [PinEntryContent] (design 01c), keeping one pairing surface.
 */
@Composable
private fun EnterIpDialog(
    onContinue: (host: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by remember { mutableStateOf("") }
    val canContinue = host.isNotBlank()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(DeskLinkTokens.ShapeCard)
                .background(DeskLinkTokens.PageRadialCenter, DeskLinkTokens.ShapeCard)
                .border(BorderStroke(1.dp, DeskLinkTokens.Border10), DeskLinkTokens.ShapeCard)
                .padding(22.dp),
        ) {
            Text(
                text = "Connect by IP",
                color = DeskLinkTokens.TextPrimary,
                fontFamily = PlexSans,
                fontSize = 19.sp,
                fontWeight = FontWeight.W600,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Enter the Mac's IP address. You'll pair with its PIN next.",
                color = DeskLinkTokens.TextSecondary,
                fontFamily = PlexSans,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))
            DialogField(
                value = host,
                onValueChange = { host = it },
                placeholder = "Mac IP address (e.g. 192.168.0.10)",
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlineButton(text = "Cancel", onClick = onDismiss, height = 46.dp)
                Spacer(Modifier.weight(1f))
                GradientButton(
                    text = "Continue",
                    onClick = { onContinue(host.trim()) },
                    enabled = canContinue,
                    height = 46.dp,
                    cornerRadius = 12.dp,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun DialogField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = DeskLinkTokens.TextPrimary,
            fontFamily = PlexSans,
            fontSize = 15.sp,
        ),
        cursorBrush = SolidColor(DeskLinkTokens.AccentLight),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DeskLinkTokens.ShapeChip)
                    .background(DeskLinkTokens.Surface04, DeskLinkTokens.ShapeChip)
                    .border(BorderStroke(1.dp, DeskLinkTokens.Border10), DeskLinkTokens.ShapeChip)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = DeskLinkTokens.TextQuaternary,
                        fontFamily = PlexSans,
                        fontSize = 15.sp,
                    )
                }
                inner()
            }
        },
    )
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
    transportMode: TransportMode,
    onTryAgain: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
            text = "Couldn't reach the DeskLink server. Make sure the Mac app is running and " +
                if (transportMode == TransportMode.LAN) {
                    "the tablet and Mac are on the same Wi-Fi network."
                } else {
                    "your USB cable is securely connected."
                },
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
        val chipShape = RoundedCornerShape(7.dp)
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
