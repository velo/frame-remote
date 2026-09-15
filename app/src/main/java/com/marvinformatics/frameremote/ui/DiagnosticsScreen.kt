package com.marvinformatics.frameremote.ui

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvinformatics.frameremote.TvViewModel
import com.marvinformatics.frameremote.UiState

/**
 * Makes "the app does nothing" debuggable without a phone in hand: shows the
 * resolved IP/MAC/pairing state and the last result of each transport.
 */
@Composable
fun DiagnosticsScreen(vm: TvViewModel, state: UiState, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Diagnostics",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(12.dp))
        DiagRow("Wi-Fi", if (state.wifi) "connected" else "NOT CONNECTED — LAN unreachable")
        DiagRow("TV IP", state.config.ip.ifBlank { "not set" })
        DiagRow("TV MAC", state.config.mac.ifBlank { "not set" })
        DiagRow("Paired", if (state.config.token.isNotBlank()) "yes (token stored)" else "no")
        DiagRow(
            "Remote socket",
            state.socketState.name.lowercase(),
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.runDiagnostics() },
            enabled = !state.diagRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.diagRunning) "Testing…" else "Test connection")
        }

        state.diag?.let { d ->
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    DiagRow("REST :8001", d.rest)
                    DiagRow("UPnP :9197", d.upnp)
                    DiagRow("WebSocket :8002", d.ws)
                    DiagRow("SSDP discovery", d.ssdp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "REST is device info and app launch; UPnP is the volume slider; " +
                "the WebSocket carries key presses. A FAILED row is where to look.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = if (value.startsWith("FAILED") || value.startsWith("NOT "))
                MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}
