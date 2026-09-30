package com.appsalad.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.appsalad.recorder.audio.Chime
import com.appsalad.recorder.data.Note
import com.appsalad.recorder.data.NoteStatus
import com.appsalad.recorder.net.OpenRouter
import com.appsalad.recorder.service.ListenService
import com.appsalad.recorder.service.Mode
import com.appsalad.recorder.ui.*
import kotlinx.coroutines.launch
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data class Detail(val id: String) : Screen
    data object Prefs : Screen
}

class MainActivity : ComponentActivity() {
    private val app get() = application as RecorderApp
    private var player: MediaPlayer? = null
    private var playingId by mutableStateOf<String?>(null)
    private var afterPermission: (() -> Unit)? = null
    private var batteryExempt by mutableStateOf(false)

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) afterPermission?.invoke()
        else Toast.makeText(this, "Recorder needs the microphone to work", Toast.LENGTH_LONG).show()
        afterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefsForTheme by app.settings.prefs.collectAsStateWithLifecycle()
            val dark = (ThemeMode.entries.firstOrNull { it.name.equals(prefsForTheme.theme, true) } ?: ThemeMode.SYSTEM).isDark()
            LaunchedEffect(dark) {
                // status/navigation bar icons follow the app theme, not only the system one
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            RecorderTheme(darkTheme = dark) {
                var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Home) }
                var tab by rememberSaveable { mutableStateOf(Tab.NOTES) }
                var keyCheck by remember { mutableStateOf("") }
                val prefs by app.settings.prefs.collectAsStateWithLifecycle()
                val notes by app.repo.notes.collectAsStateWithLifecycle()
                val listen by ListenService.state.collectAsStateWithLifecycle()
                val speaking by app.speaker.speaking.collectAsStateWithLifecycle()

                // a new recording jumps to its tab so the user sees it arrive
                LaunchedEffect(listen.mode) {
                    if (listen.mode == Mode.NOTE) tab = Tab.NOTES
                    if (listen.mode == Mode.QUESTION) tab = Tab.QUESTIONS
                }
                BackHandler(screen != Screen.Home) { screen = Screen.Home }

                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        state = listen, listening = prefs.listening, hasKey = prefs.apiKey.isNotBlank(),
                        speaking = speaking, notes = notes, tab = tab, onTab = { tab = it },
                        maxRecordMinutes = prefs.maxRecordMinutes,
                        words = prefs.commands,
                        actions = HomeActions(
                            onToggleListen = { on -> setListening(on) },
                            onRecordNote = { withMic { ListenService.send(this, ListenService.ACTION_NOTE) } },
                            onAsk = { withMic { ListenService.send(this, ListenService.ACTION_QUESTION) } },
                            onStop = { ListenService.send(this, ListenService.ACTION_STOP_RECORDING) },
                            onSilence = { app.speaker.stop() },
                            onOpen = { screen = Screen.Detail(it.id) },
                            onSettings = { screen = Screen.Prefs },
                        ),
                    )
                    is Screen.Detail -> {
                        val n = notes.firstOrNull { it.id == s.id }
                        if (n == null) { LaunchedEffect(Unit) { screen = Screen.Home } }
                        else NoteScreen(n, playingId == n.id, noteActions(n) { screen = Screen.Home }, speaking = speaking)
                    }
                    Screen.Prefs -> SettingsScreen(
                        prefs = prefs, keyCheck = keyCheck, batteryExempt = batteryExempt, unknownWords = listen.unknownWords,
                        version = "${BuildConfigCompat.versionName(this)}",
                        a = SettingsActions(
                            onBack = { screen = Screen.Home },
                            onSave = { p ->
                                app.settings.update { cur -> p.copy(listening = cur.listening, theme = cur.theme) }
                                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                                // retry anything that failed for lack of a key
                                if (p.apiKey.isNotBlank()) app.repo.notes.value
                                    .filter { it.status == NoteStatus.FAILED && it.error.startsWith("No OpenRouter key") }
                                    .forEach { app.processor.process(it.id) }
                            },
                            onTestKey = { k ->
                                keyCheck = "Checking…"
                                lifecycleScope.launch {
                                    keyCheck = runCatching { OpenRouter.checkKey(k.trim()) }.getOrElse { "Failed: ${it.message}" }
                                }
                            },
                            onBattery = { requestBatteryExemption() },
                            onTestSound = {
                                lifecycleScope.launch { Chime.play(true); kotlinx.coroutines.delay(900); Chime.play(false) }
                            },
                            onTheme = { m -> app.settings.update { it.copy(theme = m.name.lowercase()) } },
                        ),
                    )
                }
            }
        }
        // first run: ask for the microphone + notifications straight away, then start listening
        if (!hasMic()) requestPerms {
            if (app.settings.value.listening) ListenService.send(this, ListenService.ACTION_LISTEN)
        }
    }

    override fun onResume() {
        super.onResume()
        batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        // (re)start listening while we're visible: Android 14+ doesn't allow it from the background
        if (app.settings.value.listening && hasMic() && !ListenService.state.value.running) {
            ListenService.send(this, ListenService.ACTION_LISTEN)
        }
    }

    override fun onStop() {
        super.onStop()
        stopPlayback()
    }

    private fun setListening(on: Boolean) {
        if (on) withMic {
            app.settings.update { it.copy(listening = true) }
            ListenService.send(this, ListenService.ACTION_LISTEN)
            if (!batteryExempt) Toast.makeText(this, "Tip: allow background running in Settings for screen-off listening", Toast.LENGTH_LONG).show()
        } else {
            app.settings.update { it.copy(listening = false) }
            if (ListenService.state.value.running) ListenService.send(this, ListenService.ACTION_OFF)
        }
    }

    private fun noteActions(n: Note, back: () -> Unit) = NoteActions(
        onBack = back,
        onSave = { t -> app.repo.update(n.id) { it.copy(text = t.trim(), updatedAt = System.currentTimeMillis()) } },
        onShare = {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, n.title).putExtra(Intent.EXTRA_TEXT, n.shareText()), "Share note"))
        },
        onArchive = { on ->
            app.repo.update(n.id) { it.copy(archived = on) }
            Toast.makeText(this, if (on) "Archived" else "Moved back from the archive", Toast.LENGTH_SHORT).show()
            back()
        },
        onDelete = { stopPlayback(); app.repo.delete(n.id); back() },
        onRetry = { app.processor.process(n.id) },
        onAskAgain = { app.processor.ask(n.id) },
        onSpeak = { if (app.speaker.speaking.value) app.speaker.stop() else app.speaker.speak(n.answer) },
        onPlay = { togglePlayback(n) },
        onShareAudio = {
            val f = File(n.audioPath)
            if (f.exists()) {
                val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/wav")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share recording"))
            }
        },
    )

    private fun togglePlayback(n: Note) {
        if (playingId == n.id) { stopPlayback(); return }
        stopPlayback()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(n.audioPath)
                setOnCompletionListener { stopPlayback() }
                prepare(); start()
            }
            playingId = n.id
        }.onFailure { Toast.makeText(this, "Can't play: ${it.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun stopPlayback() {
        player?.runCatching { stop(); release() }
        player = null; playingId = null
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun withMic(then: () -> Unit) = if (hasMic()) then() else requestPerms(then)

    private fun requestPerms(then: () -> Unit) {
        afterPermission = then
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        askPerms.launch(perms.toTypedArray())
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        runCatching {
            startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private val ScreenSaver = androidx.compose.runtime.saveable.Saver<Screen, String>(
    save = { when (it) { Screen.Home -> "home"; Screen.Prefs -> "prefs"; is Screen.Detail -> "note:${it.id}" } },
    restore = { when { it == "prefs" -> Screen.Prefs; it.startsWith("note:") -> Screen.Detail(it.removePrefix("note:")); else -> Screen.Home } },
)

object BuildConfigCompat {
    fun versionName(a: android.content.Context): String =
        runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull() ?: "?"
}
