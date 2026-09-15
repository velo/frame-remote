package com.marvinformatics.frameremote

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marvinformatics.frameremote.ui.DiagnosticsScreen
import com.marvinformatics.frameremote.ui.FrameRemoteTheme
import com.marvinformatics.frameremote.ui.RemoteScreen
import com.marvinformatics.frameremote.ui.SettingsScreen

private enum class Screen { Remote, Settings, Diagnostics }

class MainActivity : ComponentActivity() {

    private val vm: TvViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FrameRemoteTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    App(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onPause() {
        super.onPause()
        vm.onPause()
    }
}

@Composable
private fun App(vm: TvViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.Remote) }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    if (!state.loaded) return

    when {
        screen == Screen.Diagnostics ->
            DiagnosticsScreen(vm, state, onClose = { screen = Screen.Settings })
        !state.config.isConfigured || screen == Screen.Settings ->
            SettingsScreen(
                vm,
                state,
                onClose = { screen = Screen.Remote },
                onOpenDiagnostics = { screen = Screen.Diagnostics },
            )
        else ->
            RemoteScreen(vm, state, onOpenSettings = { screen = Screen.Settings })
    }
}
