package com.appsalad.recorder.data

import android.content.Context
import com.appsalad.recorder.audio.CommandWords
import com.appsalad.recorder.net.AiClient
import com.appsalad.recorder.net.InferMux
import com.appsalad.recorder.net.OpenRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Prefs(
    /** Which service does speech-to-text and answers: "infermux" or "openrouter". */
    val provider: String = "infermux",
    /** InferMux router key (sk_…), pasted or minted by signing in through the login widget. */
    val infermuxKey: String = "",
    /** When a minted InferMux key expires (epoch ms), 0 = unknown / pasted. */
    val infermuxExpires: Long = 0,
    /** The InferMux account a minted key belongs to. */
    val infermuxEmail: String = "",
    val infermuxSttModel: String = Settings.DEFAULT_IMX_STT,
    val infermuxChatModel: String = Settings.DEFAULT_IMX_CHAT,
    /** OpenRouter key. */
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
    /** Every recording (note or question) stops and is transcribed after this long. */
    val maxRecordMinutes: Int = 1,
    /** Chime when a recording starts and stops. */
    val sounds: Boolean = true,
    /** Buzz when a recording starts and stops (for noisy places). */
    val vibrate: Boolean = true,
    val systemPrompt: String = Settings.DEFAULT_SYSTEM,
    /** "system", "light" or "dark". */
    val theme: String = "system",
    /** Spoken commands, normalised (see [CommandWords.normalize]). */
    val notePhrase: String = CommandWords.DEFAULT_NOTE,
    val questionPhrase: String = CommandWords.DEFAULT_QUESTION,
    val stopPhrase: String = CommandWords.DEFAULT_STOP,
) {
    val commands: CommandWords get() = CommandWords(notePhrase, questionPhrase, stopPhrase)

    val usesInferMux: Boolean get() = provider != "openrouter"
    val infermuxExpired: Boolean get() = infermuxExpires in 1..System.currentTimeMillis()
    val providerName: String get() = if (usesInferMux) "InferMux" else "OpenRouter"

    /** The configured AI service, or null (with [missingKeyMessage]) when it has no usable key. */
    fun aiClient(): AiClient? = when {
        usesInferMux -> infermuxKey.takeIf { it.isNotBlank() && !infermuxExpired }
            ?.let { InferMux(it, infermuxSttModel, infermuxChatModel) }
        else -> apiKey.takeIf { it.isNotBlank() }?.let { OpenRouter(it, sttModel, chatModel) }
    }

    val missingKeyMessage: String get() = when {
        usesInferMux && infermuxExpired -> "$NO_KEY: the InferMux key expired — sign in again in Settings, then Retry"
        usesInferMux -> "$NO_KEY: sign in to InferMux (or paste a key) in Settings, then Retry"
        else -> "$NO_KEY: set your OpenRouter key in Settings, then Retry"
    }

    companion object {
        /** Prefix of the error on notes that failed only for want of a key (retried after sign-in). */
        const val NO_KEY = "No AI key"
    }
}

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
        // installs from before InferMux keep using OpenRouter if they had a key for it
        provider = sp.getString("provider", null) ?: if (sp.getString("apiKey", "").isNullOrBlank()) "infermux" else "openrouter",
        infermuxKey = sp.getString("infermuxKey", "") ?: "",
        infermuxExpires = sp.getLong("infermuxExpires", 0),
        infermuxEmail = sp.getString("infermuxEmail", "") ?: "",
        infermuxSttModel = sp.getString("infermuxSttModel", DEFAULT_IMX_STT) ?: DEFAULT_IMX_STT,
        infermuxChatModel = sp.getString("infermuxChatModel", DEFAULT_IMX_CHAT) ?: DEFAULT_IMX_CHAT,
        apiKey = sp.getString("apiKey", "") ?: "",
        sttModel = sp.getString("sttModel", DEFAULT_STT) ?: DEFAULT_STT,
        chatModel = sp.getString("chatModel", DEFAULT_CHAT) ?: DEFAULT_CHAT,
        language = sp.getString("language", "") ?: "",
        speakAnswers = sp.getBoolean("speakAnswers", true),
        keepAudio = sp.getBoolean("keepAudio", true),
        listening = sp.getBoolean("listening", true),
        maxRecordMinutes = sp.getInt("maxRecordMinutes", 1),
        sounds = sp.getBoolean("sounds", true),
        vibrate = sp.getBoolean("vibrate", true),
        systemPrompt = sp.getString("systemPrompt", DEFAULT_SYSTEM) ?: DEFAULT_SYSTEM,
        theme = sp.getString("theme", "system") ?: "system",
        notePhrase = sp.getString("notePhrase", null) ?: CommandWords.DEFAULT_NOTE,
        questionPhrase = sp.getString("questionPhrase", null) ?: CommandWords.DEFAULT_QUESTION,
        stopPhrase = sp.getString("stopPhrase", null) ?: CommandWords.DEFAULT_STOP,
    )

    fun update(change: (Prefs) -> Prefs) {
        val p = change(_prefs.value)
        sp.edit()
            .putString("provider", if (p.provider == "openrouter") "openrouter" else "infermux")
            .putString("infermuxKey", p.infermuxKey.trim())
            .putLong("infermuxExpires", p.infermuxExpires)
            .putString("infermuxEmail", p.infermuxEmail)
            .putString("infermuxSttModel", p.infermuxSttModel.trim().ifEmpty { DEFAULT_IMX_STT })
            .putString("infermuxChatModel", p.infermuxChatModel.trim().ifEmpty { DEFAULT_IMX_CHAT })
            .putString("apiKey", p.apiKey.trim())
            .putString("sttModel", p.sttModel.trim().ifEmpty { DEFAULT_STT })
            .putString("chatModel", p.chatModel.trim().ifEmpty { DEFAULT_CHAT })
            .putString("language", p.language.trim())
            .putBoolean("speakAnswers", p.speakAnswers)
            .putBoolean("keepAudio", p.keepAudio)
            .putBoolean("listening", p.listening)
            .putInt("maxRecordMinutes", p.maxRecordMinutes.coerceIn(1, 180))
            .putBoolean("sounds", p.sounds)
            .putBoolean("vibrate", p.vibrate)
            .putString("systemPrompt", p.systemPrompt)
            .putString("theme", p.theme)
            .apply { CommandWords.of(p.notePhrase, p.questionPhrase, p.stopPhrase).let {
                putString("notePhrase", it.note); putString("questionPhrase", it.question); putString("stopPhrase", it.stop) } }
            .apply()
        _prefs.value = read()
    }

    companion object {
        const val DEFAULT_STT = "openai/gpt-4o-mini-transcribe"
        /** InferMux transcribes through a chat model that takes audio input. */
        const val DEFAULT_IMX_STT = "google/gemini-3.5-flash"
        const val DEFAULT_IMX_CHAT = "google/gemini-3.5-flash"
        val IMX_STT_SUGGESTIONS = listOf("google/gemini-3.5-flash", "google/gemini-3.1-flash-lite", "openai/gpt-audio-mini")
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
