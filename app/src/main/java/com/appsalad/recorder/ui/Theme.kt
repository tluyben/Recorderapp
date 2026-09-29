package com.appsalad.recorder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFE5484D)
val Listening = Color(0xFF3DD68C)
val AskBlue = Color(0xFF5B9DFF)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = AskBlue,
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1B2127),
    surfaceContainer = Color(0xFF171C21),
    surfaceContainerHigh = Color(0xFF1E252C),
    onSurface = Color(0xFFE6E9EC),
    onSurfaceVariant = Color(0xFF9AA4AE),
    outline = Color(0xFF3A444E),
)

@Composable
fun RecorderTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)
