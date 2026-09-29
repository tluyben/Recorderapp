package com.appsalad.recorder.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Prefs(
    val apiKey: String = "",
    val sttModel: String = Settings.DEFAULT_STT,
    val chatModel: String = Settings.DEFAULT_CHAT,
    /** ISO-639-1 hint for the transcriber, "" = auto-detect. */
    val language: String = "",
    val speakAnswers: Boolean = true,
    /** Keep the WAV next to the note after a successful transcription. */
    val keepAudio: Boolean = true,
    /** The user wants always-on listening; the service restarts it when the app opens. */
    val listening: Boolean = true,
    val maxNoteMinutes: Int = 30,
    val systemPrompt: String = Settings.DEFAULT_SYSTEM,
    /** "system", "light" or "dark". */
    val theme: String = "system",
)

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    init {
        // 1.0.0 shipped with listening off by default; always-on is the point of the app
        if (!sp.getBoolean("listenDefaultOn", false)) sp.edit().putBoolean("listening", true).putBoolean("listenDefaultOn", true).apply()
    }
    private val _prefs = MutableStateFlow(read())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()
    val value: Prefs get() = _prefs.value

    private fun read() = Prefs(
        apiKey = sp.getString("apiKey", "") ?: "",
        sttModel = sp.getString("sttModel", DEFAULT_STT) ?: DEFAULT_STT,
        chatModel = sp.getString("chatModel", DEFAULT_CHAT) ?: DEFAULT_CHAT,
        language = sp.getString("language", "") ?: "",
        speakAnswers = sp.getBoolean("speakAnswers", true),
        keepAudio = sp.getBoolean("keepAudio", true),
        listening = sp.getBoolean("listening", true),
        maxNoteMinutes = sp.getInt("maxNoteMinutes", 30),
        systemPrompt = sp.getString("systemPrompt", DEFAULT_SYSTEM) ?: DEFAULT_SYSTEM,
        theme = sp.getString("theme", "system") ?: "system",
    )

    fun update(change: (Prefs) -> Prefs) {
        val p = change(_prefs.value)
        sp.edit()
            .putString("apiKey", p.apiKey.trim())
            .putString("sttModel", p.sttModel.trim().ifEmpty { DEFAULT_STT })
            .putString("chatModel", p.chatModel.trim().ifEmpty { DEFAULT_CHAT })
            .putString("language", p.language.trim())
            .putBoolean("speakAnswers", p.speakAnswers)
            .putBoolean("keepAudio", p.keepAudio)
            .putBoolean("listening", p.listening)
            .putInt("maxNoteMinutes", p.maxNoteMinutes.coerceIn(1, 180))
            .putString("systemPrompt", p.systemPrompt)
            .putString("theme", p.theme)
            .apply()
        _prefs.value = read()
    }

    companion object {
        const val DEFAULT_STT = "openai/gpt-4o-mini-transcribe"
        const val DEFAULT_CHAT = "google/gemini-3.5-flash"
        const val DEFAULT_SYSTEM = "You are a voice assistant. Your answer is read aloud by text-to-speech, " +
            "so answer in plain spoken sentences: no markdown, no lists with symbols, no code blocks, no URLs " +
            "unless asked. Be concise — a few sentences unless the question needs more."
        val STT_SUGGESTIONS = listOf(
            "openai/gpt-4o-mini-transcribe", "openai/gpt-4o-transcribe", "openai/whisper-large-v3-turbo",
            "openai/whisper-large-v3", "google/gemini-3.5-transcribe", "mistralai/voxtral-mini-transcribe",
            "deepgram/nova-3", "qwen/qwen3-asr-flash-2026-02-10",
        )
        val CHAT_SUGGESTIONS = listOf(
            "google/gemini-3.5-flash", "google/gemini-3.1-flash-lite", "openai/gpt-5-mini",
            "anthropic/claude-sonnet-5.5", "openrouter/auto",
        )
    }
}
