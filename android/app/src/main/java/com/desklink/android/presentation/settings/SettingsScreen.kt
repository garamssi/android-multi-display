package com.desklink.android.presentation.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.SolidColor
import com.desklink.android.domain.model.DisplayConfig
import com.desklink.android.domain.model.DisplayRotation
import com.desklink.android.domain.model.TransportMode
import com.desklink.android.domain.transport.DiscoveredServer
import com.desklink.android.presentation.components.GhostTextButton
import com.desklink.android.presentation.components.MonoText
import com.desklink.android.presentation.components.ResolutionRadioCard
import com.desklink.android.presentation.components.SegmentedControl
import com.desklink.android.presentation.components.StatusDot
import com.desklink.android.presentation.theme.DeskLinkTokens
import com.desklink.android.presentation.theme.PlexSans

private data class ResolutionOption(
    val name: String,
    val width: Int,
    val height: Int,
    val isNative: Boolean,
)

private fun presetName(width: Int, height: Int): String = when {
    width >= 2560 -> "QHD"
    width >= 1920 -> "FHD+"
    else -> "HD"
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val discoveredServers by viewModel.discoveredServers.collectAsState()

    val resolutionOptions = buildList {
        add(ResolutionOption("Native", state.nativeWidth, state.nativeHeight, isNative = true))
        SettingsUiState.RESOLUTION_PRESETS
            .filter { (w, h) -> w <= state.nativeWidth && h <= state.nativeHeight }
            .filterNot { (w, h) -> w == state.nativeWidth && h == state.nativeHeight }
            .forEach { (w, h) -> add(ResolutionOption(presetName(w, h), w, h, isNative = false)) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeskLinkTokens.AppBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            SettingsHeader(
                onBack = onBack,
                onReset = {
                    viewModel.useNativeResolution()
                    viewModel.setFps(60)
                    viewModel.setCodec(DisplayConfig.Codec.HEVC)
                    viewModel.setBitrate(DisplayConfig.recommendedBitrateKbps(state.nativeWidth))
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(DeskLinkTokens.Border06),
            )

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val twoColumn = maxWidth >= 1024.dp
                val small = maxWidth < 600.dp
                val scroll = rememberScrollState()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(
                            horizontal = if (twoColumn) 34.dp else if (small) 20.dp else 24.dp,
                            vertical = if (twoColumn) 30.dp else 26.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(30.dp),
                ) {
                    Column {
                        SectionLabel("Connection")
                        Spacer(Modifier.height(12.dp))
                        ConnectionSegmented(
                            transportMode = state.transportMode,
                            onSelect = viewModel::setTransportMode,
                        )
                    }

                    if (state.transportMode == TransportMode.USB) {
                        AdaptiveTwoColumn(
                            twoColumn = twoColumn,
                            left = { m ->
                                ResolutionColumn(
                                    modifier = m,
                                    options = resolutionOptions,
                                    selectedWidth = state.width,
                                    selectedHeight = state.height,
                                    isNativeSelected = state.isNativeSelected,
                                    singleColumn = small,
                                    onSelectNative = viewModel::useNativeResolution,
                                    onSelectPreset = viewModel::setResolution,
                                )
                            },
                            right = { m ->
                                StreamColumn(
                                    modifier = m,
                                    state = state,
                                    onSetFps = viewModel::setFps,
                                    onSetBitrate = viewModel::setBitrate,
                                    onSetCodec = viewModel::setCodec,
                                    onSetScrollSensitivity = viewModel::setScrollSensitivity,
                                    onSetNaturalScroll = viewModel::setNaturalScroll,
                                    onSetTouchInput = viewModel::setTouchInputEnabled,
                                    onSetRotation = viewModel::setDisplayRotation,
                                )
                            },
                        )
                    } else {
                        AdaptiveTwoColumn(
                            twoColumn = twoColumn,
                            left = { m ->
                                WifiConnectionColumn(
                                    modifier = m,
                                    manualHost = state.manualHost,
                                    discoveredServers = discoveredServers,
                                    onManualHostChange = viewModel::setManualHost,
                                    onStartDiscovery = viewModel::startDiscovery,
                                    onStopDiscovery = viewModel::stopDiscovery,
                                    onSelectServer = viewModel::selectDiscoveredServer,
                                )
                            },
                            right = { m ->
                                Column(modifier = m, verticalArrangement = Arrangement.spacedBy(26.dp)) {
                                    ResolutionColumn(
                                        options = resolutionOptions,
                                        selectedWidth = state.width,
                                        selectedHeight = state.height,
                                        isNativeSelected = state.isNativeSelected,
                                        singleColumn = small,
                                        onSelectNative = viewModel::useNativeResolution,
                                        onSelectPreset = viewModel::setResolution,
                                    )
                                    StreamColumn(
                                        state = state,
                                        onSetFps = viewModel::setFps,
                                        onSetBitrate = viewModel::setBitrate,
                                        onSetCodec = viewModel::setCodec,
                                        onSetScrollSensitivity = viewModel::setScrollSensitivity,
                                        onSetNaturalScroll = viewModel::setNaturalScroll,
                                        onSetTouchInput = viewModel::setTouchInputEnabled,
                                        onSetRotation = viewModel::setDisplayRotation,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdaptiveTwoColumn(
    twoColumn: Boolean,
    left: @Composable (Modifier) -> Unit,
    right: @Composable (Modifier) -> Unit,
) {
    if (twoColumn) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            left(Modifier.weight(1f))
            right(Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(30.dp)) {
            left(Modifier.fillMaxWidth())
            right(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit, onReset: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .padding(horizontal = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val squareShape = RoundedCornerShape(DeskLinkTokens.RadiusSquareButton)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(squareShape)
                .background(color = DeskLinkTokens.Surface04, shape = squareShape)
                .border(BorderStroke(1.dp, DeskLinkTokens.Border10), squareShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.ChevronLeft,
                contentDescription = "Back",
                tint = DeskLinkTokens.TextPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = "Settings",
            color = DeskLinkTokens.TextPrimary,
            fontFamily = PlexSans,
            fontSize = 22.sp,
            fontWeight = FontWeight.W600,
        )
        Spacer(Modifier.weight(1f))
        GhostTextButton(text = "Reset to defaults", onClick = onReset, fontSize = 14.sp)
    }
}

@Composable
private fun ConnectionSegmented(
    transportMode: TransportMode,
    onSelect: (TransportMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(DeskLinkTokens.ShapeSegmentTrack)
            .background(DeskLinkTokens.Surface05, DeskLinkTokens.ShapeSegmentTrack)
            .border(BorderStroke(1.dp, DeskLinkTokens.Border07), DeskLinkTokens.ShapeSegmentTrack)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ConnSegment(Icons.Outlined.Cable, "USB", transportMode == TransportMode.USB) {
            onSelect(TransportMode.USB)
        }
        ConnSegment(Icons.Outlined.Wifi, "Wi-Fi (LAN)", transportMode == TransportMode.LAN) {
            onSelect(TransportMode.LAN)
        }
    }
}

@Composable
private fun ConnSegment(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val base = Modifier
        .height(44.dp)
        .clip(DeskLinkTokens.ShapeSegment)
    val styled = if (selected) {
        base.background(DeskLinkTokens.AccentVertical, DeskLinkTokens.ShapeSegment)
    } else {
        base
    }
    Row(
        modifier = styled.clickable(onClick = onClick).padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) Color.White else DeskLinkTokens.TextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = if (selected) Color.White else DeskLinkTokens.TextSecondary,
            fontFamily = PlexSans,
            fontSize = 14.5.sp,
            fontWeight = if (selected) FontWeight.W600 else FontWeight.W500,
        )
    }
}

@Composable
private fun ResolutionColumn(
    modifier: Modifier = Modifier,
    options: List<ResolutionOption>,
    selectedWidth: Int,
    selectedHeight: Int,
    isNativeSelected: Boolean,
    singleColumn: Boolean,
    onSelectNative: () -> Unit,
    onSelectPreset: (Int, Int) -> Unit,
) {
    val perRow = if (singleColumn) 1 else 2
    Column(modifier = modifier) {
        SectionLabel("Resolution")
        Spacer(Modifier.height(14.dp))
        options.chunked(perRow).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                rowItems.forEach { opt ->
                    val selected = if (opt.isNative) {
                        isNativeSelected
                    } else {
                        !isNativeSelected && selectedWidth == opt.width && selectedHeight == opt.height
                    }
                    ResolutionRadioCard(
                        name = opt.name,
                        value = "${opt.width} × ${opt.height}",
                        selected = selected,
                        onClick = {
                            if (opt.isNative) onSelectNative() else onSelectPreset(opt.width, opt.height)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowItems.size < perRow) {
                    repeat(perRow - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(11.dp))
        }
        InfoNote(
            text = "Options adapt to the connected tablet — DeskLink detects the panel's " +
                "native resolution and offers matching presets automatically.",
        )
    }
}

@Composable
private fun StreamColumn(
    modifier: Modifier = Modifier,
    state: SettingsUiState,
    onSetFps: (Int) -> Unit,
    onSetBitrate: (Int) -> Unit,
    onSetCodec: (DisplayConfig.Codec) -> Unit,
    onSetScrollSensitivity: (Float) -> Unit,
    onSetNaturalScroll: (Boolean) -> Unit,
    onSetTouchInput: (Boolean) -> Unit,
    onSetRotation: (DisplayRotation) -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        Column {
            SectionLabel("Frame rate")
            Spacer(Modifier.height(14.dp))
            SegmentedControl(
                options = SettingsUiState.FPS_OPTIONS,
                selected = state.fps,
                onSelect = onSetFps,
            ) { fps, isSelected ->
                SegmentLabel(text = "$fps fps", isSelected = isSelected)
            }
        }

        Column {
            SectionLabel("Bitrate")
            Spacer(Modifier.height(14.dp))
            SegmentedControl(
                options = SettingsUiState.BITRATE_OPTIONS,
                selected = SettingsUiState.BITRATE_OPTIONS.firstOrNull { it.kbps == state.bitrateKbps }
                    ?: SettingsUiState.BITRATE_OPTIONS.last(),
                onSelect = { onSetBitrate(it.kbps) },
                itemHeight = 58.dp,
            ) { option, isSelected ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = option.label,
                        color = if (isSelected) Color.White else DeskLinkTokens.TextSecondary,
                        fontFamily = PlexSans,
                        fontSize = 14.5.sp,
                        fontWeight = if (isSelected) FontWeight.W600 else FontWeight.W500,
                    )
                    MonoText(
                        text = "${option.kbps / 1000} Mbps",
                        color = if (isSelected) Color.White.copy(alpha = 0.8f) else DeskLinkTokens.TextQuaternary,
                        fontSize = 11.5.sp,
                    )
                }
            }
        }

        Column {
            SectionLabel("Codec")
            Spacer(Modifier.height(14.dp))
            SegmentedControl(
                options = listOf(DisplayConfig.Codec.HEVC, DisplayConfig.Codec.H264),
                selected = state.codec,
                onSelect = onSetCodec,
            ) { codec, isSelected ->
                val label = if (codec == DisplayConfig.Codec.HEVC) "H.265 · HEVC" else "H.264 · AVC"
                SegmentLabel(text = label, isSelected = isSelected)
            }
        }

        Column {
            SectionLabel("Scroll speed")
            Spacer(Modifier.height(14.dp))
            SegmentedControl(
                options = SettingsUiState.SCROLL_SPEED_OPTIONS,
                selected = SettingsUiState.SCROLL_SPEED_OPTIONS
                    .firstOrNull { it.sensitivity == state.scrollSensitivity }
                    ?: SettingsUiState.SCROLL_SPEED_OPTIONS
                        .first { it.sensitivity == SettingsUiState().scrollSensitivity },
                onSelect = { onSetScrollSensitivity(it.sensitivity) },
            ) { option, isSelected ->
                SegmentLabel(text = option.label, isSelected = isSelected)
            }
        }

        Column {
            SectionLabel("Scroll direction")
            Spacer(Modifier.height(14.dp))
            SegmentedControl(
                options = SettingsUiState.SCROLL_DIRECTION_OPTIONS,
                selected = SettingsUiState.SCROLL_DIRECTION_OPTIONS
                    .first { it.natural == state.naturalScroll },
                onSelect = { onSetNaturalScroll(it.natural) },
            ) { option, isSelected ->
                SegmentLabel(text = option.label, isSelected = isSelected)
            }
        }

        Column {
            SectionLabel("Touch input")
            Spacer(Modifier.height(14.dp))
            SegmentedControl(
                options = SettingsUiState.TOUCH_INPUT_OPTIONS,
                selected = SettingsUiState.TOUCH_INPUT_OPTIONS
                    .first { it.enabled == state.touchInputEnabled },
                onSelect = { onSetTouchInput(it.enabled) },
            ) { option, isSelected ->
                SegmentLabel(text = option.label, isSelected = isSelected)
            }
        }

        Column {
            SectionLabel("Rotation")
            Spacer(Modifier.height(14.dp))
            SegmentedControl(
                options = SettingsUiState.ROTATION_OPTIONS,
                selected = state.rotation,
                onSelect = onSetRotation,
            ) { rotation, isSelected ->
                SegmentLabel(text = "${rotation.degrees}°", isSelected = isSelected)
            }
        }

        SummaryChip(state = state)
    }
}

@Composable
private fun WifiConnectionColumn(
    modifier: Modifier = Modifier,
    manualHost: String,
    discoveredServers: List<DiscoveredServer>,
    onManualHostChange: (String) -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onSelectServer: (DiscoveredServer) -> Unit,
) {
    Column(modifier = modifier) {
        SectionLabel("Server address")
        Spacer(Modifier.height(12.dp))
        MacIpField(value = manualHost, onValueChange = onManualHostChange)
        Spacer(Modifier.height(22.dp))
        DiscoverySection(
            servers = discoveredServers,
            onStartDiscovery = onStartDiscovery,
            onStopDiscovery = onStopDiscovery,
            onSelectServer = onSelectServer,
        )
        Spacer(Modifier.height(16.dp))
        WarningNote(
            text = "Wi-Fi is experimental. Traffic is encrypted (TLS); pair with the PIN " +
                "shown on the Mac and use only on a trusted network. USB stays the default.",
        )
    }
}

@Composable
private fun DiscoverySection(
    servers: List<DiscoveredServer>,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onSelectServer: (DiscoveredServer) -> Unit,
) {
    val context = LocalContext.current
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onStartDiscovery() }

    DisposableEffect(Unit) {
        onDispose { onStopDiscovery() }
    }

    Column {
        SectionLabel("Discovered on Wi-Fi")
        Spacer(Modifier.height(10.dp))
        GhostTextButton(
            text = "Find my Mac",
            onClick = {
                val granted = !needsPermission || ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) onStartDiscovery() else permissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            },
            fontSize = 14.sp,
        )
        servers.forEach { server ->
            Spacer(Modifier.height(8.dp))
            DiscoveredServerRow(server = server, onClick = { onSelectServer(server) })
        }
    }
}

@Composable
private fun DiscoveredServerRow(server: DiscoveredServer, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DeskLinkTokens.ShapeChip)
            .background(color = DeskLinkTokens.Surface04, shape = DeskLinkTokens.ShapeChip)
            .border(BorderStroke(1.dp, DeskLinkTokens.Border10), DeskLinkTokens.ShapeChip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                color = DeskLinkTokens.TextPrimary,
                fontFamily = PlexSans,
                fontSize = 14.sp,
                fontWeight = FontWeight.W600,
            )
            MonoText(
                text = server.osVersion?.let { "${server.host} · $it" } ?: server.host,
                color = DeskLinkTokens.TextQuaternary,
                fontSize = 12.sp,
            )
        }
        Text(
            text = "Use",
            color = DeskLinkTokens.AccentLight,
            fontFamily = PlexSans,
            fontSize = 13.sp,
            fontWeight = FontWeight.W600,
        )
    }
}

@Composable
private fun MacIpField(value: String, onValueChange: (String) -> Unit) {
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
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DeskLinkTokens.ShapeChip)
                    .background(color = DeskLinkTokens.Surface03, shape = DeskLinkTokens.ShapeChip)
                    .border(BorderStroke(1.dp, DeskLinkTokens.Border10), DeskLinkTokens.ShapeChip)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = "Mac IP address (e.g. 192.168.0.10)",
                            color = DeskLinkTokens.TextQuaternary,
                            fontFamily = PlexSans,
                            fontSize = 15.sp,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
private fun WarningNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DeskLinkTokens.ShapeChip)
            .background(color = DeskLinkTokens.WarningChipBg, shape = DeskLinkTokens.ShapeChip)
            .border(BorderStroke(1.dp, DeskLinkTokens.WarningChipBorder), DeskLinkTokens.ShapeChip)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = DeskLinkTokens.Warning,
            modifier = Modifier
                .size(16.dp)
                .padding(top = 1.dp),
        )
        Text(
            text = text,
            color = DeskLinkTokens.TextBody,
            fontFamily = PlexSans,
            fontSize = 12.5.sp,
        )
    }
}

@Composable
private fun SegmentLabel(text: String, isSelected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = text,
            color = if (isSelected) Color.White else DeskLinkTokens.TextSecondary,
            fontFamily = PlexSans,
            fontSize = 14.5.sp,
            fontWeight = if (isSelected) FontWeight.W600 else FontWeight.W500,
        )
    }
}

@Composable
private fun SummaryChip(state: SettingsUiState) {
    val tail = if (state.transportMode == TransportMode.LAN) {
        "TLS"
    } else {
        "${state.bitrateKbps / 1000}Mbps"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DeskLinkTokens.ShapeCard)
            .background(color = DeskLinkTokens.SuccessChipBg, shape = DeskLinkTokens.ShapeCard)
            .border(BorderStroke(1.dp, DeskLinkTokens.SuccessChipBorder), DeskLinkTokens.ShapeCard)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        StatusDot(color = DeskLinkTokens.Success, size = 8.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Estimated stream — ",
                color = DeskLinkTokens.TextBody,
                fontFamily = PlexSans,
                fontSize = 13.5.sp,
            )
            MonoText(
                text = "${state.width}×${state.height} · ${state.fps}fps · $tail",
                color = DeskLinkTokens.TextPrimary,
                fontSize = 13.5.sp,
            )
        }
    }
}

@Composable
private fun InfoNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DeskLinkTokens.ShapeChip)
            .background(color = DeskLinkTokens.Surface03, shape = DeskLinkTokens.ShapeChip)
            .border(BorderStroke(1.dp, DeskLinkTokens.Border06), DeskLinkTokens.ShapeChip)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = DeskLinkTokens.AccentLight,
            modifier = Modifier
                .size(16.dp)
                .padding(top = 1.dp),
        )
        Text(
            text = text,
            color = DeskLinkTokens.TextSecondary,
            fontFamily = PlexSans,
            fontSize = 12.5.sp,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    MonoText(
        text = text,
        color = DeskLinkTokens.TextQuaternary,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.W500,
        letterSpacingEm = 0.16f,
        uppercase = true,
    )
}
