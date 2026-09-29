package com.appsalad.recorder.data

import org.json.JSONObject

enum class NoteKind { NOTE, QUESTION }

enum class NoteStatus { RECORDING, TRANSCRIBING, ANSWERING, READY, FAILED }

data class Note(
    val id: String,
    val kind: NoteKind,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val text: String = "",
    /** The AI answer, for questions. */
    val answer: String = "",
    val status: NoteStatus = NoteStatus.READY,
    val error: String = "",
    /** Absolute path of the WAV recording, or "" once it has been removed. */
    val audioPath: String = "",
    val durationMs: Long = 0,
    val archived: Boolean = false,
) {
    val title: String
        get() = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.let { if (it.length > 80) it.take(79).trimEnd() + "…" else it }
            ?: when (status) {
                NoteStatus.RECORDING -> "Recording…"
                NoteStatus.TRANSCRIBING -> "Transcribing…"
                NoteStatus.FAILED -> "Transcription failed"
                else -> "(empty)"
            }

    /** What the share sheet gets. */
    fun shareText(): String =
        if (kind == NoteKind.QUESTION && answer.isNotBlank()) "Q: ${text.trim()}\n\nA: ${answer.trim()}"
        else text.trim()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind.name).put("createdAt", createdAt).put("updatedAt", updatedAt)
        .put("text", text).put("answer", answer).put("status", status.name).put("error", error)
        .put("audioPath", audioPath).put("durationMs", durationMs).put("archived", archived)

    companion object {
        fun fromJson(o: JSONObject) = Note(
            id = o.getString("id"),
            kind = runCatching { NoteKind.valueOf(o.optString("kind")) }.getOrDefault(NoteKind.NOTE),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt", o.optLong("createdAt")),
            text = o.optString("text"),
            answer = o.optString("answer"),
            status = runCatching { NoteStatus.valueOf(o.optString("status")) }.getOrDefault(NoteStatus.READY),
            error = o.optString("error"),
            audioPath = o.optString("audioPath"),
            durationMs = o.optLong("durationMs"),
            archived = o.optBoolean("archived"),
        )
    }
}
