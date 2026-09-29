package com.appsalad.recorder

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Reads answers aloud with the phone's own text-to-speech engine. */
class Speaker(context: Context) {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    private var ready = false
    private var pending: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.getDefault()
            pending?.let { pending = null; speak(it) }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { _speaking.value = true }
            override fun onDone(id: String?) { if (id == LAST) _speaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { _speaking.value = false }
            override fun onStop(id: String?, interrupted: Boolean) { _speaking.value = false }
        })
    }

    fun speak(text: String) {
        if (!ready) { pending = text; return }
        tts.stop()
        // engines cap one utterance at getMaxSpeechInputLength(); split on sentences
        val max = TextToSpeech.getMaxSpeechInputLength() - 1
        val parts = text.split(Regex("(?<=[.!?])\\s+")).flatMap { it.chunked(max) }.filter { it.isNotBlank() }
        _speaking.value = true
        parts.forEachIndexed { i, p ->
            tts.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null,
                if (i == parts.lastIndex) LAST else "part$i")
        }
        if (parts.isEmpty()) _speaking.value = false
    }

    fun stop() { tts.stop(); _speaking.value = false }

    private companion object { const val LAST = "last" }
}
