package com.marvinformatics.frameremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvinformatics.frameremote.TvViewModel
import com.marvinformatics.frameremote.UiState

@Composable
fun SettingsScreen(vm: TvViewModel, state: UiState, onClose: () -> Unit) {
    var ip by remember(state.config.ip) { mutableStateOf(state.config.ip) }
    var mac by remember(state.config.mac) { mutableStateOf(state.config.mac) }

    LaunchedEffect(Unit) {
        if (!state.config.isConfigured) vm.discover()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.config.isConfigured) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(
                "TV setup",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Everything stays on your LAN — the app talks straight to the TV, no cloud, no account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.discover() }, enabled = !state.discovering) {
                Text(if (state.discovering) "Searching…" else "Find my TV")
            }
            if (state.discovering) CircularProgressIndicator(Modifier.height(20.dp))
        }

        state.discovered.forEach { tv ->
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                onClick = { vm.selectTv(tv); onClose() },
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(tv.name.ifBlank { tv.modelName }, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${tv.ip} · ${tv.modelName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("Manual entry", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it.trim() },
            label = { Text("TV IP address") },
            placeholder = { Text("e.g. 192.168.1.50") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = mac,
            onValueChange = { mac = it.trim() },
            label = { Text("TV MAC address (for Wake-on-LAN)") },
            placeholder = { Text("auto-filled from the TV when possible") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.saveManual(ip, mac); onClose() },
            enabled = ip.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }

        if (state.config.isConfigured) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Pairing", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.config.token.isBlank())
                    "Not paired yet — the first key press pops an Allow prompt on the TV. Accept it once and the token is remembered."
                else
                    "Paired. The TV remembers this remote.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.forgetPairing() }) { Text("Forget pairing") }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Power-on from standby uses Wake-on-LAN and needs the TV's " +
                "\"Power On with Mobile\" / network standby setting enabled.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.config.isConfigured) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onClose) { Text("Done") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
