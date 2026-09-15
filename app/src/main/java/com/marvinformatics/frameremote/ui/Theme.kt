package com.marvinformatics.frameremote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFE8B44A)
private val Night = Color(0xFF0B0E11)
private val Panel = Color(0xFF161B21)
private val PanelHigh = Color(0xFF1F262E)
private val Ink = Color(0xFFE7EAEE)
private val InkDim = Color(0xFF9AA4AF)

private val Scheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF221A05),
    primaryContainer = PanelHigh,
    onPrimaryContainer = Amber,
    secondary = InkDim,
    onSecondary = Night,
    background = Night,
    onBackground = Ink,
    surface = Panel,
    onSurface = Ink,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = InkDim,
    outline = Color(0xFF39424C),
    error = Color(0xFFE57373),
)

@Composable
fun FrameRemoteTheme(content: @Composable () -> Unit) {
    
    MaterialTheme(colorScheme = Scheme, content = content)
}
