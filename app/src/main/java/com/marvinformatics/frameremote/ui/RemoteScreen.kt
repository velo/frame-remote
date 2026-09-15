package com.marvinformatics.frameremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marvinformatics.frameremote.TizenApps
import com.marvinformatics.frameremote.TvViewModel
import com.marvinformatics.frameremote.UiState
import com.marvinformatics.frameremote.VolumeControl

@Composable
fun RemoteScreen(vm: TvViewModel, state: UiState, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Header(state, onOpenSettings)

        Spacer(Modifier.weight(1f))

        if (state.config.isConfigured && !state.reachable) {
            WakeTvButton(vm)
            Spacer(Modifier.height(20.dp))
        }

        AppLaunchRow(vm)

        Spacer(Modifier.height(20.dp))

        VolumeCard(vm, state)

        Spacer(Modifier.height(24.dp))

        DPad(vm)

        Spacer(Modifier.height(16.dp))

        BottomRow(vm, state)

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Header(state: UiState, onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = state.config.name.ifBlank { "Frame Remote" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (dotColor, label) = when {
                    !state.config.isConfigured -> Color(0xFF9AA4AF) to "not configured"
                    state.reachable && state.powerState == "on" -> Color(0xFF6BCB77) to "on"
                    state.reachable -> Color(0xFFE8B44A) to state.powerState
                    else -> Color(0xFFE57373) to "unreachable"
                }
                Box(
                    Modifier
                        .size(8.dp)
                        .background(dotColor, CircleShape)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = if (state.config.isConfigured) "${state.config.ip} · $label" else label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun AppLaunchRow(vm: TvViewModel) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick = { vm.launchApp(TizenApps.PLEX, "Plex") },
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Plex", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        FilledTonalButton(
            onClick = { vm.launchApp(TizenApps.YOUTUBE, "YouTube") },
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("YouTube", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun VolumeCard(vm: TvViewModel, state: UiState) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        if (state.volumeControl == VolumeControl.KEYS) {
            // UPnP refused absolute volume (e.g. HTTP 401 while the TV
            // distrusts this device) — step with TV keys instead of showing
            // a slider that silently does nothing.
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.toggleMute() }) {
                        Icon(
                            if (state.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (state.muted) "Unmute" else "Mute",
                            tint = if (state.muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(
                        onClick = { vm.volumeDown() },
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Vol −", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.size(10.dp))
                    FilledTonalButton(
                        onClick = { vm.volumeUp() },
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Vol +", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                }
                Text(
                    "Absolute volume unavailable (TV refused UPnP) — using TV keys. Pairing usually fixes this.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.toggleMute() }) {
                    Icon(
                        if (state.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (state.muted) "Unmute" else "Mute",
                        tint = if (state.muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                var dragging by remember { mutableStateOf(false) }
                var dragValue by remember { mutableStateOf(0f) }
                val shown = if (dragging) dragValue else (state.volume ?: 0).toFloat()
                Slider(
                    value = shown,
                    onValueChange = {
                        dragging = true
                        dragValue = it
                        vm.setVolume(it.toInt())
                    },
                    onValueChangeFinished = { dragging = false },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = (if (dragging) dragValue.toInt() else state.volume)?.toString() ?: "–",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DPad(vm: TvViewModel) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(260.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(260.dp),
        ) {}
        DirButton(Icons.Filled.KeyboardArrowUp, "Up", Modifier.align(Alignment.TopCenter)) { vm.sendKey("KEY_UP") }
        DirButton(Icons.Filled.KeyboardArrowDown, "Down", Modifier.align(Alignment.BottomCenter)) { vm.sendKey("KEY_DOWN") }
        DirButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left", Modifier.align(Alignment.CenterStart)) { vm.sendKey("KEY_LEFT") }
        DirButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right", Modifier.align(Alignment.CenterEnd)) { vm.sendKey("KEY_RIGHT") }
        FilledIconButton(
            onClick = { vm.sendKey("KEY_ENTER") },
            modifier = Modifier.size(92.dp),
            shape = CircleShape,
        ) {
            Text("OK", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DirButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = modifier.size(72.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun WakeTvButton(vm: TvViewModel) {
    FilledTonalButton(
        onClick = { vm.wakeTv() },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text("Wake TV (Wake-on-LAN)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BottomRow(vm: TvViewModel, state: UiState) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        LabeledAction(label = "Back") {
            FilledTonalIconButton(
                onClick = { vm.sendKey("KEY_RETURN") },
                modifier = Modifier.size(64.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        // On a Frame TV, KEY_POWER toggles Art Mode — the TV never powers
        // down from software. Label the button for what it actually does.
        val artLabel = when (state.artMode) {
            true -> "Art Mode · on"
            false -> "Art Mode · off"
            null -> "Art Mode"
        }
        LabeledAction(label = artLabel) {
            FilledIconButton(
                onClick = { vm.toggleArtMode() },
                enabled = state.reachable,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (state.artMode == true) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (state.artMode == true) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Filled.Wallpaper, contentDescription = "Toggle Art Mode", modifier = Modifier.size(30.dp))
            }
        }
        LabeledAction(label = "Home") {
            FilledTonalIconButton(
                onClick = { vm.sendKey("KEY_HOME") },
                modifier = Modifier.size(64.dp),
            ) {
                Icon(Icons.Filled.Home, contentDescription = "Home")
            }
        }
    }
}

@Composable
private fun LabeledAction(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
