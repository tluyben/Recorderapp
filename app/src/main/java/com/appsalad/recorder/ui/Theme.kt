package com.appsalad.recorder.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFE5484D)
val AskBlue = Color(0xFF5B9DFF)

/** The user's choice in Settings → Appearance. */
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

private val LocalDark = staticCompositionLocalOf { true }

/** Colours outside the Material scheme, one value per theme. */
object Tones {
    val listening: Color @Composable @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFF3DD68C) else Color(0xFF15965A)
    val warn: Color @Composable @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFFFFB84D) else Color(0xFFB86E00)
    val bannerBg: Color @Composable @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFF3A2A12) else Color(0xFFFFF1DB)
    val bannerTitle: Color @Composable @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFFFFD699) else Color(0xFF7A4A00)
    val bannerText: Color @Composable @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFFE8CFA6) else Color(0xFF8A6530)
}

private val dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = AskBlue,
    secondaryContainer = Color(0xFF3F2629),
    onSecondaryContainer = Color(0xFFFFD9DA),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1B2127),
    surfaceContainer = Color(0xFF171C21),
    surfaceContainerHigh = Color(0xFF1E252C),
    onSurface = Color(0xFFE6E9EC),
    onSurfaceVariant = Color(0xFF9AA4AE),
    outline = Color(0xFF3A444E),
)

private val light = lightColorScheme(
    primary = Color(0xFFD93A40),
    onPrimary = Color.White,
    secondary = Color(0xFF2F7BEA),
    secondaryContainer = Color(0xFFFBE1E2),
    onSecondaryContainer = Color(0xFF6B1418),
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFF6F7F9),
    surfaceVariant = Color(0xFFE6EAEE),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFECEFF3),
    onSurface = Color(0xFF15191D),
    onSurfaceVariant = Color(0xFF5A6570),
    outline = Color(0xFFC3CAD1),
)

@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun RecorderTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalDark provides darkTheme) {
        MaterialTheme(colorScheme = if (darkTheme) dark else light, content = content)
    }
