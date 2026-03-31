package com.desklink.android.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.desklink.android.domain.model.DisplayConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Resolution
            SectionTitle("Resolution")
            SettingsUiState.RESOLUTION_PRESETS.forEach { (w, h) ->
                RadioRow(
                    label = "${w}x${h}",
                    selected = state.width == w && state.height == h,
                    onClick = { viewModel.setResolution(w, h) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // FPS
            SectionTitle("Frame Rate")
            SettingsUiState.FPS_OPTIONS.forEach { fps ->
                RadioRow(
                    label = "${fps} fps",
                    selected = state.fps == fps,
                    onClick = { viewModel.setFps(fps) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bitrate
            SectionTitle("Bitrate")
            SettingsUiState.BITRATE_OPTIONS.forEach { bitrate ->
                RadioRow(
                    label = "${bitrate / 1000} Mbps",
                    selected = state.bitrateKbps == bitrate,
                    onClick = { viewModel.setBitrate(bitrate) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Codec
            SectionTitle("Codec")
            RadioRow(
                label = "H.265 (HEVC)",
                selected = state.codec == DisplayConfig.Codec.HEVC,
                onClick = { viewModel.setCodec(DisplayConfig.Codec.HEVC) },
            )
            RadioRow(
                label = "H.264 (AVC)",
                selected = state.codec == DisplayConfig.Codec.H264,
                onClick = { viewModel.setCodec(DisplayConfig.Codec.H264) },
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}
