package com.appsalad.recorder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appsalad.recorder.data.Note
import com.appsalad.recorder.data.NoteKind
import com.appsalad.recorder.data.NoteStatus

class NoteActions(
    val onBack: () -> Unit = {},
    val onSave: (String) -> Unit = {},
    val onShare: () -> Unit = {},
    val onArchive: (Boolean) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onAskAgain: () -> Unit = {},
    val onSpeak: () -> Unit = {},
    val onPlay: () -> Unit = {},
    val onShareAudio: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(n: Note, playing: Boolean, a: NoteActions) {
    var text by remember(n.id) { mutableStateOf(n.text) }
    // pick up a transcription that lands while the note is open, unless the user is editing
    var edited by remember(n.id) { mutableStateOf(false) }
    LaunchedEffect(n.text) { if (!edited) text = n.text }
    var confirmDelete by remember { mutableStateOf(false) }
    val dirty = edited && text != n.text
    val busy = n.status == NoteStatus.TRANSCRIBING || n.status == NoteStatus.ANSWERING || n.status == NoteStatus.RECORDING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (n.kind == NoteKind.NOTE) "Note" else "Question") },
                navigationIcon = { IconButton(onClick = a.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = a.onShare, enabled = n.text.isNotBlank()) { Icon(Icons.Outlined.Share, "Share") }
                    IconButton(onClick = { a.onArchive(!n.archived) }) {
                        Icon(if (n.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, if (n.archived) "Unarchive" else "Archive")
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (dirty) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { text = n.text; edited = false }, Modifier.weight(1f)) { Text("Discard") }
                    Button(onClick = { a.onSave(text); edited = false }, Modifier.weight(1f)) { Text("Save") }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDate(n.createdAt) + if (n.durationMs > 0) " · ${duration(n.durationMs)}" else "",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                StatusChip(n.status)
                if (n.archived) { Spacer(Modifier.width(6.dp)); Text("archived", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (n.status == NoteStatus.FAILED) {
                Spacer(Modifier.height(10.dp))
                Surface(color = Accent.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(n.error, Modifier.weight(1f), color = Accent, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = a.onRetry) { Text("Retry") }
                    }
                }
            }
            if (busy) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; edited = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = if (n.kind == NoteKind.NOTE) 260.dp else 90.dp),
                label = { Text(if (n.kind == NoteKind.NOTE) "Text" else "Your question") },
                placeholder = { Text(if (busy) "Transcribing…" else "") },
                shape = RoundedCornerShape(14.dp),
            )
            if (n.kind == NoteKind.QUESTION) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Answer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = AskBlue)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = a.onAskAgain, enabled = !busy && n.text.isNotBlank()) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Ask again")
                    }
                    IconButton(onClick = a.onSpeak, enabled = n.answer.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, "Read aloud") }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
                    Text(n.answer.ifBlank { if (busy) "Thinking…" else "No answer yet" }, Modifier.fillMaxWidth().padding(14.dp),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (n.audioPath.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = a.onPlay) {
                        Icon(if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(6.dp)); Text(if (playing) "Stop" else "Play recording")
                    }
                    OutlinedButton(onClick = a.onShareAudio) {
                        Icon(Icons.Outlined.AudioFile, null); Spacer(Modifier.width(6.dp)); Text("Share audio")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this ${if (n.kind == NoteKind.NOTE) "note" else "question"}?") },
        text = { Text("The text and the recording are removed from this phone. This can't be undone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; a.onDelete() }) { Text("Delete", color = Accent) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
