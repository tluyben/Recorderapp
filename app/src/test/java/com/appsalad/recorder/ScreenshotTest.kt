package com.appsalad.recorder

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.appsalad.recorder.audio.CommandWords
import com.appsalad.recorder.data.Note
import com.appsalad.recorder.data.NoteKind
import com.appsalad.recorder.data.NoteStatus
import com.appsalad.recorder.data.Prefs
import com.appsalad.recorder.service.ListenState
import com.appsalad.recorder.service.Mode
import com.appsalad.recorder.ui.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the screens on the JVM (Robolectric native graphics) with sample data and
 * writes PNGs to build/shots/. Run: ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
abstract class ScreenshotBase {
    @get:Rule val rule = createComposeRule()
    protected val now = 1_790_000_000_000L
    protected val notes = listOf(
        Note("1", NoteKind.NOTE, now - 60_000, text = "Groceries for the weekend\nMilk, eggs, sourdough, two avocados and coffee beans from the market.", durationMs = 14_000),
        Note("2", NoteKind.NOTE, now - 3_600_000, text = "", status = NoteStatus.TRANSCRIBING, durationMs = 42_000, audioPath = "/x.wav"),
        Note("3", NoteKind.NOTE, now - 86_400_000, text = "Idea: let the app send a weekly digest of notes by email. Ask Quan what he thinks about pricing.", durationMs = 31_000),
        Note("4", NoteKind.NOTE, now - 2 * 86_400_000L, status = NoteStatus.FAILED, error = "No OpenRouter key — set it in Settings, then Retry", durationMs = 8_000, audioPath = "/y.wav"),
        Note("5", NoteKind.QUESTION, now - 120_000, text = "How long should I boil an egg for a runny yolk?", answer = "About six minutes in boiling water, then straight into cold water so it stops cooking.", durationMs = 5_000),
        Note("6", NoteKind.QUESTION, now - 7_200_000, text = "What's the capital of Peru?", answer = "Lima is the capital of Peru.", durationMs = 3_000),
    )

    protected fun shot(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        // the pulse and timer animate forever; drive the clock by hand so the rule goes idle
        rule.mainClock.autoAdvance = false
        rule.setContent { RecorderTheme(darkTheme = dark) { content() } }
        rule.mainClock.advanceTimeBy(600)
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }
}

@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class MobileShots : ScreenshotBase() {
    private val suffix = "mobile"
    @Test fun homeListening() = shot("home-listening-$suffix") {
        HomeScreen(ListenState(running = true, heard = "so what time is the meeting"), listening = true, hasKey = true,
            speaking = false, notes = notes, tab = Tab.NOTES, onTab = {}, actions = HomeActions(), now = { now })
    }
    @Test fun homeRecording() = shot("home-recording-note-$suffix") {
        HomeScreen(ListenState(running = true, mode = Mode.NOTE, recordingSince = now - 23_000, heard = "remember to call the plumber"),
            listening = true, hasKey = true, speaking = false, notes = notes, tab = Tab.NOTES, onTab = {}, actions = HomeActions(), now = { now })
    }
    @Test fun homeFirstRun() = shot("home-first-run-$suffix") {
        HomeScreen(ListenState(), listening = false, hasKey = false, speaking = false, notes = emptyList(), tab = Tab.NOTES,
            onTab = {}, actions = HomeActions(), now = { now })
    }
    @Test fun questions() = shot("questions-speaking-$suffix") {
        HomeScreen(ListenState(running = true, mode = Mode.SPEAKING), listening = true, hasKey = true, speaking = true,
            notes = notes, tab = Tab.QUESTIONS, onTab = {}, actions = HomeActions(), now = { now })
    }
    @Test fun noteDetail() = shot("note-detail-$suffix") { NoteScreen(notes[0].copy(audioPath = "/a.wav"), false, NoteActions()) }
    @Test fun questionDetail() = shot("question-detail-$suffix") { NoteScreen(notes[4].copy(audioPath = "/q.wav"), false, NoteActions()) }
    @Test fun failedDetail() = shot("note-failed-$suffix") { NoteScreen(notes[3], false, NoteActions()) }
    @Test fun settings() = shot("settings-$suffix") {
        SettingsScreen(Prefs(apiKey = "sk-or-v1-0123456789abcdef", listening = true, theme = "dark"), "Key OK (recorder) · used $0.42",
            batteryExempt = false, version = "1.0.0", a = SettingsActions())
    }

    @Test fun homeLight() = shot("light-home-listening-$suffix", dark = false) {
        HomeScreen(ListenState(running = true, heard = "so what time is the meeting"), listening = true, hasKey = false,
            speaking = false, notes = notes, tab = Tab.NOTES, onTab = {}, actions = HomeActions(), now = { now })
    }
    @Test fun recordingLight() = shot("light-home-recording-question-$suffix", dark = false) {
        HomeScreen(ListenState(running = true, mode = Mode.QUESTION, recordingSince = now - 7_000), listening = true, hasKey = true,
            speaking = false, notes = notes, tab = Tab.QUESTIONS, onTab = {}, actions = HomeActions(), now = { now })
    }
    @Test fun questionLight() = shot("light-question-detail-$suffix", dark = false) { NoteScreen(notes[4].copy(audioPath = "/q.wav"), false, NoteActions()) }
    @Test fun settingsLight() = shot("light-settings-$suffix", dark = false) {
        SettingsScreen(Prefs(apiKey = "sk-or-v1-0123456789abcdef", theme = "light"), "Key OK (recorder) · used $0.42",
            batteryExempt = false, version = "1.0.2", a = SettingsActions())
    }

    @Test fun stopReading() = shot("home-stop-reading-$suffix") {
        HomeScreen(ListenState(running = true, mode = Mode.SPEAKING), listening = true, hasKey = true, speaking = true,
            notes = notes, tab = Tab.QUESTIONS, onTab = {}, actions = HomeActions(), now = { now })
    }
    @Test fun questionStopReading() = shot("question-stop-reading-$suffix") {
        NoteScreen(notes[4].copy(audioPath = "/q.wav"), false, NoteActions(), speaking = true)
    }
    @Config(qualifiers = "w390dp-h1900dp-xxhdpi")
    @Test fun settingsRecording() = shot("settings-recording-$suffix") {
        SettingsScreen(Prefs(apiKey = "sk-or-v1-0123456789abcdef", theme = "dark"), "", batteryExempt = false, version = "1.0.3", a = SettingsActions())
    }
    @Config(qualifiers = "w390dp-h1900dp-xxhdpi")
    @Test fun settingsRecordingLight() = shot("light-settings-recording-$suffix", dark = false) {
        SettingsScreen(Prefs(apiKey = "sk-or-v1-0123456789abcdef", theme = "light", maxRecordMinutes = 5, sounds = false), "",
            batteryExempt = true, version = "1.0.3", a = SettingsActions())
    }

    @Test fun homeCustomWords() = shot("home-custom-words-$suffix") {
        HomeScreen(ListenState(running = true, heard = "memo please", unknownWords = listOf("gpt")), listening = true, hasKey = true,
            speaking = false, notes = notes, tab = Tab.NOTES, onTab = {}, actions = HomeActions(), now = { now },
            words = CommandWords.of("memo please", "hey gpt", "over and out"))
    }
    @Config(qualifiers = "w390dp-h2400dp-xxhdpi")
    @Test fun settingsVoiceCommands() = shot("settings-voice-commands-$suffix") {
        SettingsScreen(Prefs(apiKey = "sk-or-v1-0123456789abcdef", theme = "dark", notePhrase = "memo please", questionPhrase = "hey gpt",
            stopPhrase = "over and out"), "", batteryExempt = true, version = "1.0.5", a = SettingsActions(), unknownWords = listOf("gpt"))
    }
}

@Config(qualifiers = "w1280dp-h800dp-mdpi")
class DesktopShots : ScreenshotBase() {
    @Test fun homeListening() = shot("home-listening-desktop") {
        HomeScreen(ListenState(running = true, heard = "so what time is the meeting"), listening = true, hasKey = true,
            speaking = false, notes = notes, tab = Tab.NOTES, onTab = {}, actions = HomeActions(), now = { now })
    }
    @Test fun homeRecording() = shot("home-recording-note-desktop") {
        HomeScreen(ListenState(running = true, mode = Mode.NOTE, recordingSince = now - 23_000), listening = true, hasKey = true,
            speaking = false, notes = notes, tab = Tab.NOTES, onTab = {}, actions = HomeActions(), now = { now })
    }
    @Test fun questionDetail() = shot("question-detail-desktop") { NoteScreen(notes[4].copy(audioPath = "/q.wav"), false, NoteActions()) }
    @Test fun settings() = shot("settings-desktop") {
        SettingsScreen(Prefs(apiKey = "sk-or-v1-0123456789abcdef"), "", batteryExempt = true, version = "1.0.0", a = SettingsActions())
    }
    @Test fun homeLight() = shot("light-home-listening-desktop", dark = false) {
        HomeScreen(ListenState(running = true, heard = "so what time is the meeting"), listening = true, hasKey = true,
            speaking = false, notes = notes, tab = Tab.NOTES, onTab = {}, actions = HomeActions(), now = { now })
    }
    @Test fun recordingLight() = shot("light-home-recording-question-desktop", dark = false) {
        HomeScreen(ListenState(running = true, mode = Mode.QUESTION, recordingSince = now - 7_000), listening = true, hasKey = true,
            speaking = false, notes = notes, tab = Tab.QUESTIONS, onTab = {}, actions = HomeActions(), now = { now })
    }
    @Test fun questionLight() = shot("light-question-detail-desktop", dark = false) { NoteScreen(notes[4].copy(audioPath = "/q.wav"), false, NoteActions()) }
    @Test fun settingsLight() = shot("light-settings-desktop", dark = false) {
        SettingsScreen(Prefs(apiKey = "sk-or-v1-0123456789abcdef", theme = "light"), "", batteryExempt = true, version = "1.0.2", a = SettingsActions())
    }
    @Test fun settingsDarkTheme() = shot("settings-theme-dark-desktop") {
        SettingsScreen(Prefs(apiKey = "sk-or-v1-0123456789abcdef", theme = "dark"), "", batteryExempt = true, version = "1.0.2", a = SettingsActions())
    }
    @Test fun stopReading() = shot("home-stop-reading-desktop") {
        HomeScreen(ListenState(running = true, mode = Mode.SPEAKING), listening = true, hasKey = true, speaking = true,
            notes = notes, tab = Tab.QUESTIONS, onTab = {}, actions = HomeActions(), now = { now })
    }
    @Test fun questionStopReading() = shot("question-stop-reading-desktop") {
        NoteScreen(notes[4].copy(audioPath = "/q.wav"), false, NoteActions(), speaking = true)
    }
    @Config(qualifiers = "w1280dp-h1400dp-mdpi")
    @Test fun settingsRecording() = shot("settings-recording-desktop") {
        SettingsScreen(Prefs(apiKey = "sk-or-v1-0123456789abcdef", theme = "dark"), "", batteryExempt = false, version = "1.0.3", a = SettingsActions())
    }
    @Config(qualifiers = "w1280dp-h1500dp-mdpi")
    @Test fun settingsVoiceCommands() = shot("settings-voice-commands-desktop") {
        SettingsScreen(Prefs(apiKey = "sk-or-v1-0123456789abcdef", theme = "dark", notePhrase = "memo please", questionPhrase = "hey gpt",
            stopPhrase = "over and out"), "", batteryExempt = true, version = "1.0.5", a = SettingsActions(), unknownWords = listOf("gpt"))
    }
    @Config(qualifiers = "w1280dp-h1500dp-mdpi")
    @Test fun settingsVoiceCommandsLight() = shot("light-settings-voice-commands-desktop", dark = false) {
        SettingsScreen(Prefs(apiKey = "sk-or-v1-0123456789abcdef", theme = "light"), "", batteryExempt = true, version = "1.0.5", a = SettingsActions())
    }
    @Test fun homeCustomWords() = shot("home-custom-words-desktop") {
        HomeScreen(ListenState(running = true, heard = "memo please"), listening = true, hasKey = true,
            speaking = false, notes = notes, tab = Tab.NOTES, onTab = {}, actions = HomeActions(), now = { now },
            words = CommandWords.of("memo please", "jarvis", "over and out"))
    }
}
