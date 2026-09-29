package com.appsalad.recorder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.appsalad.recorder.data.Prefs
import com.appsalad.recorder.data.Settings

class SettingsActions(
    val onBack: () -> Unit = {},
    val onSave: (Prefs) -> Unit = {},
    val onTestKey: (String) -> Unit = {},
    val onBattery: () -> Unit = {},
    /** Applied at once, without Save. */
    val onTheme: (ThemeMode) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(prefs: Prefs, keyCheck: String, batteryExempt: Boolean, version: String, a: SettingsActions) {
    // edits stay local until Save; the theme and the listening switch apply at once elsewhere
    var p by remember { mutableStateOf(prefs) }
    var showKey by remember { mutableStateOf(false) }
    val dirty = p.copy(listening = prefs.listening, theme = prefs.theme) != prefs
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = a.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = { if (dirty) TextButton(onClick = { a.onSave(p) }) { Text("Save") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("Appearance")
            val mode = ThemeMode.entries.firstOrNull { it.name.equals(prefs.theme, true) } ?: ThemeMode.SYSTEM
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = m == mode, onClick = { a.onTheme(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        icon = { SegmentedButtonDefaults.Icon(m == mode) { Icon(when (m) {
                            ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                            ThemeMode.LIGHT -> Icons.Outlined.LightMode
                            ThemeMode.DARK -> Icons.Outlined.DarkMode
                        }, null, Modifier.size(SegmentedButtonDefaults.IconSize)) } },
                    ) { Text(m.label) }
                }
            }

            Section("OpenRouter")
            OutlinedTextField(
                value = p.apiKey, onValueChange = { p = p.copy(apiKey = it) },
                label = { Text("API key") }, placeholder = { Text("sk-or-v1-…") }, singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { IconButton(onClick = { showKey = !showKey }) {
                    Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show key") } },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { a.onTestKey(p.apiKey) }, enabled = p.apiKey.isNotBlank()) { Text("Test key") }
                Spacer(Modifier.width(12.dp))
                Text(keyCheck, style = MaterialTheme.typography.bodySmall,
                    color = if (keyCheck.startsWith("Key OK")) Tones.listening else MaterialTheme.colorScheme.primary)
            }
            Text("Get a key at openrouter.ai/keys. It is stored only on this phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ModelField("Speech-to-text model", p.sttModel, Settings.STT_SUGGESTIONS) { p = p.copy(sttModel = it) }
            ModelField("AI model for questions", p.chatModel, Settings.CHAT_SUGGESTIONS) { p = p.copy(chatModel = it) }
            OutlinedTextField(
                value = p.language, onValueChange = { p = p.copy(language = it.take(5)) },
                label = { Text("Language (e.g. en, nl — empty = auto)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            )
            OutlinedTextField(
                value = p.systemPrompt, onValueChange = { p = p.copy(systemPrompt = it) },
                label = { Text("Instructions for the AI") }, minLines = 3,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            )

            Section("Behaviour")
            Toggle("Read answers aloud", "Uses the phone's text-to-speech voice", p.speakAnswers) { p = p.copy(speakAnswers = it) }
            Toggle("Keep recordings", "Keep the audio with each note after it is transcribed", p.keepAudio) { p = p.copy(keepAudio = it) }
            OutlinedTextField(
                value = p.maxNoteMinutes.toString(),
                onValueChange = { v -> v.filter(Char::isDigit).take(3).toIntOrNull()?.let { p = p.copy(maxNoteMinutes = it) } },
                label = { Text("Longest note (minutes) — stops by itself after this") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            )

            Section("Always-on listening")
            Text("Voice commands are recognised on the phone with an offline Vosk model — no audio leaves the phone " +
                "until you say “Take note” or “Question”. For listening with the screen off, let Recorder ignore battery optimisation.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = a.onBattery, enabled = !batteryExempt) {
                Text(if (batteryExempt) "Battery optimisation is off ✓" else "Allow running in the background")
            }
            Spacer(Modifier.height(8.dp))
            Text("Recorder $version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Toggle(title: String, sub: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = v, onCheckedChange = on)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelField(label: String, value: String, suggestions: List<String>, on: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value, onValueChange = on, label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable), shape = RoundedCornerShape(12.dp),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            suggestions.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { on(s); open = false }) }
        }
    }
}
