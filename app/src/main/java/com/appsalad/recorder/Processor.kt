package com.appsalad.recorder

import com.appsalad.recorder.audio.Commands
import com.appsalad.recorder.data.NoteKind
import com.appsalad.recorder.data.NoteStatus
import com.appsalad.recorder.data.NotesRepo
import com.appsalad.recorder.data.Settings
import com.appsalad.recorder.net.OpenRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * Turns a finished recording into text (OpenRouter STT) and, for a question, into a
 * spoken answer (OpenRouter chat + TTS). Runs in the app scope, not the service, so it
 * also works for Retry while listening is off.
 */
class Processor(
    private val scope: CoroutineScope,
    private val repo: NotesRepo,
    private val settings: Settings,
    private val speaker: Speaker,
) {
    private val gate = Semaphore(2)

    fun process(id: String) {
        repo.update(id) { it.copy(status = NoteStatus.TRANSCRIBING, error = "") }
        scope.launch { gate.withPermit { run(id) } }
    }

    /** Re-ask an existing question (e.g. after editing its text). */
    fun ask(id: String) {
        repo.update(id) { it.copy(status = NoteStatus.ANSWERING, error = "") }
        scope.launch { gate.withPermit { answer(id) } }
    }

    private suspend fun run(id: String) {
        val note = repo.get(id) ?: return
        val p = settings.value
        if (p.apiKey.isBlank()) return fail(id, "No OpenRouter key — set it in Settings, then Retry")
        // a question that already has text (typed, or transcribed before) goes straight to the AI
        if (note.text.isBlank()) {
            val audio = File(note.audioPath)
            if (note.audioPath.isEmpty() || !audio.exists()) return fail(id, "The recording is gone")
            val raw = try {
                OpenRouter.transcribe(p.apiKey, p.sttModel, audio, p.language,
                    prompt = if (note.kind == NoteKind.NOTE) "A voice note." else "A question for an AI assistant.")
            } catch (e: Exception) {
                return fail(id, "Transcription: ${e.message}")
            }
            val text = Commands.cleanTranscript(note.kind, raw, p.commands)
            if (text.isBlank()) return fail(id, "Nothing was heard in the recording")
            repo.update(id) { it.copy(text = text, updatedAt = System.currentTimeMillis()) }
            if (!p.keepAudio && note.kind == NoteKind.NOTE) dropAudio(id)
        }
        if (note.kind == NoteKind.QUESTION) {
            repo.update(id) { it.copy(status = NoteStatus.ANSWERING) }
            answer(id)
        } else {
            repo.update(id) { it.copy(status = NoteStatus.READY) }
        }
    }

    private suspend fun answer(id: String) {
        val note = repo.get(id) ?: return
        val p = settings.value
        if (p.apiKey.isBlank()) return fail(id, "No OpenRouter key — set it in Settings, then Retry")
        val a = try {
            OpenRouter.chat(p.apiKey, p.chatModel, p.systemPrompt, note.text)
        } catch (e: Exception) {
            return fail(id, "AI: ${e.message}")
        }
        repo.update(id) { it.copy(answer = a, status = NoteStatus.READY, updatedAt = System.currentTimeMillis()) }
        if (!p.keepAudio) dropAudio(id)
        if (p.speakAnswers && a.isNotBlank()) speaker.speak(a)
    }

    private fun dropAudio(id: String) {
        repo.get(id)?.audioPath?.takeIf { it.isNotEmpty() }?.let { File(it).delete() }
        repo.update(id) { it.copy(audioPath = "") }
    }

    private fun fail(id: String, msg: String) {
        repo.update(id) { it.copy(status = NoteStatus.FAILED, error = msg) }
    }
}
