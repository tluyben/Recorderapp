package com.appsalad.recorder.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appsalad.recorder.data.Note
import com.appsalad.recorder.data.NoteKind
import com.appsalad.recorder.data.NoteStatus
import com.appsalad.recorder.service.ListenState
import com.appsalad.recorder.service.Mode
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab(val label: String) { NOTES("Notes"), QUESTIONS("Questions"), ARCHIVE("Archive") }

class HomeActions(
    val onToggleListen: (Boolean) -> Unit = {},
    val onRecordNote: () -> Unit = {},
    val onAsk: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onSilence: () -> Unit = {},
    val onOpen: (Note) -> Unit = {},
    val onSettings: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: ListenState,
    listening: Boolean,
    hasKey: Boolean,
    speaking: Boolean,
    notes: List<Note>,
    tab: Tab,
    onTab: (Tab) -> Unit,
    actions: HomeActions,
    now: () -> Long = System::currentTimeMillis,
    maxRecordMinutes: Int = 1,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recorder", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = actions.onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        val shown = notes.filter {
            when (tab) {
                Tab.NOTES -> !it.archived && it.kind == NoteKind.NOTE
                Tab.QUESTIONS -> !it.archived && it.kind == NoteKind.QUESTION
                Tab.ARCHIVE -> it.archived
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!hasKey) item { KeyBanner(actions.onSettings) }
            item { StatusCard(state, listening, speaking, actions, now, maxRecordMinutes) }
            item {
                val counts = Tab.entries.associateWith { t ->
                    notes.count { when (t) {
                        Tab.NOTES -> !it.archived && it.kind == NoteKind.NOTE
                        Tab.QUESTIONS -> !it.archived && it.kind == NoteKind.QUESTION
                        Tab.ARCHIVE -> it.archived
                    } }
                }
                PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
                    Tab.entries.forEach { t ->
                        Tab(selected = t == tab, onClick = { onTab(t) },
                            text = { Text(if (counts[t]!! > 0) "${t.label} ${counts[t]}" else t.label) })
                    }
                }
            }
            if (shown.isEmpty()) item { EmptyState(tab) }
            items(shown, key = { it.id }) { NoteRow(it) { actions.onOpen(it) } }
        }
    }
}

@Composable
private fun KeyBanner(onSettings: () -> Unit) {
    Surface(
        color = Tones.bannerBg, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSettings),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Key, null, tint = Tones.warn)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Add your OpenRouter key", fontWeight = FontWeight.SemiBold, color = Tones.bannerTitle)
                Text("Needed to transcribe notes and answer questions. Recording works without it.",
                    style = MaterialTheme.typography.bodySmall, color = Tones.bannerText)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = Tones.bannerTitle)
        }
    }
}

@Composable
private fun StatusCard(s: ListenState, listening: Boolean, speaking: Boolean, a: HomeActions, now: () -> Long, maxMin: Int) {
    val recording = s.mode == Mode.NOTE || s.mode == Mode.QUESTION
    val tint = when {
        recording -> if (s.mode == Mode.NOTE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        s.running && listening -> Tones.listening
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(tint, active = recording || (s.running && listening))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            s.loading -> "Starting…"
                            s.mode == Mode.NOTE -> "Recording note"
                            s.mode == Mode.QUESTION -> "Listening to your question"
                            s.mode == Mode.SPEAKING || speaking -> "Reading the answer"
                            s.running && listening -> "Listening"
                            else -> "Not listening"
                        },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            recording -> "Say “Stop stop” when you're done"
                            s.mode == Mode.SPEAKING || speaking -> "Tap Stop reading, or say “Stop stop”"
                            s.running && listening -> "Say “Take note” or “Question”"
                            else -> "Turn on to use voice commands"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (recording) Column(horizontalAlignment = Alignment.End) {
                    Elapsed(s.recordingSince, now)
                    Text("of ${duration(maxMin * 60_000L)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else Switch(checked = listening, onCheckedChange = a.onToggleListen)
            }
            if (s.error.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(s.error, color = Accent, style = MaterialTheme.typography.bodySmall)
            }
            if (s.running && s.heard.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("heard: “${s.heard}”", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (recording) {
                    Button(onClick = a.onStop, modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = tint)) {
                        Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop")
                    }
                } else if (s.mode == Mode.SPEAKING || speaking) {
                    Button(onClick = a.onSilence, modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(8.dp))
                        Text("Stop reading", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(onClick = a.onRecordNote, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Outlined.Mic, null); Spacer(Modifier.width(8.dp)); Text("Note")
                    }
                    Button(onClick = a.onAsk, modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                        Icon(Icons.Outlined.QuestionAnswer, null); Spacer(Modifier.width(8.dp)); Text("Ask AI")
                    }
                }
            }
        }
    }
}

@Composable
private fun PulseDot(color: Color, active: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(16.dp).alpha(if (active) a else 1f).clip(CircleShape).background(color))
    }
}

@Composable
private fun Elapsed(since: Long, now: () -> Long) {
    var t by remember { mutableLongStateOf(now()) }
    LaunchedEffect(since) { while (true) { t = now(); delay(500) } }
    Text(duration(t - since), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
}

@Composable
private fun EmptyState(tab: Tab) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            when (tab) { Tab.NOTES -> Icons.AutoMirrored.Outlined.Notes; Tab.QUESTIONS -> Icons.Outlined.QuestionAnswer; Tab.ARCHIVE -> Icons.Outlined.Archive },
            null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            when (tab) {
                Tab.NOTES -> "No notes yet. Say “Take note”, talk, then “Stop stop”."
                Tab.QUESTIONS -> "No questions yet. Say “Question”, ask, then “Stop stop”."
                Tab.ARCHIVE -> "Archived notes show up here."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun NoteRow(n: Note, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (n.kind == NoteKind.NOTE) Icons.AutoMirrored.Outlined.Notes else Icons.Outlined.QuestionAnswer, null,
                    Modifier.size(16.dp), tint = if (n.kind == NoteKind.NOTE) Accent else AskBlue)
                Spacer(Modifier.width(6.dp))
                Text(formatDate(n.createdAt), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (n.durationMs > 0) Text(" · ${duration(n.durationMs)}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                StatusChip(n.status)
            }
            Spacer(Modifier.height(6.dp))
            Text(n.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val body = if (n.kind == NoteKind.QUESTION && n.answer.isNotBlank()) n.answer
                else n.text.lines().drop(1).joinToString(" ").trim()
            val sub = if (n.status == NoteStatus.FAILED) n.error else body
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (n.status == NoteStatus.FAILED) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun StatusChip(s: NoteStatus) {
    val (label, color) = when (s) {
        NoteStatus.RECORDING -> "recording" to Accent
        NoteStatus.TRANSCRIBING -> "transcribing" to Tones.warn
        NoteStatus.ANSWERING -> "thinking" to AskBlue
        NoteStatus.FAILED -> "failed" to Accent
        NoteStatus.READY -> return
    }
    Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp))
}

fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatDate(t: Long): String {
    val d = Date(t)
    val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    return if (SimpleDateFormat("yyyyMMdd", Locale.US).format(d) == today) SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
    else SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(d)
}
