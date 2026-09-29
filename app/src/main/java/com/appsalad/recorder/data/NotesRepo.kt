package com.appsalad.recorder.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * All notes live in one JSON file in the app's private storage, rewritten atomically on
 * every change. A few thousand notes stay well under a megabyte, so this is simpler and
 * sturdier than a database.
 */
class NotesRepo(private val dir: File) {
    private val file = File(dir, "notes.json")
    private val lock = Any()
    private val _notes = MutableStateFlow(load())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    val audioDir: File get() = File(dir, "audio").apply { mkdirs() }

    private fun load(): List<Note> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            val loaded = (0 until arr.length()).map { Note.fromJson(arr.getJSONObject(it)) }
            // a crash mid-recording or mid-request leaves a note in a transient state
            loaded.map {
                when (it.status) {
                    NoteStatus.RECORDING, NoteStatus.TRANSCRIBING, NoteStatus.ANSWERING ->
                        it.copy(status = NoteStatus.FAILED, error = "Interrupted — tap Retry")
                    else -> it
                }
            }
        }.getOrElse { emptyList() }.sortedByDescending { it.createdAt }
    }

    private fun save(list: List<Note>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(dir, "notes.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    fun get(id: String): Note? = _notes.value.firstOrNull { it.id == id }

    fun create(kind: NoteKind, audioPath: String, status: NoteStatus = NoteStatus.RECORDING): Note {
        val n = Note(id = UUID.randomUUID().toString(), kind = kind, createdAt = System.currentTimeMillis(),
            audioPath = audioPath, status = status)
        synchronized(lock) {
            val list = listOf(n) + _notes.value
            save(list); _notes.value = list
        }
        return n
    }

    fun newAudioFile(): File = File(audioDir, "rec-${System.currentTimeMillis()}.wav")

    fun update(id: String, change: (Note) -> Note): Note? = synchronized(lock) {
        var out: Note? = null
        val list = _notes.value.map { if (it.id == id) change(it).also { n -> out = n } else it }
        if (out != null) { save(list); _notes.value = list }
        out
    }

    fun delete(id: String) = synchronized(lock) {
        val n = get(id) ?: return
        if (n.audioPath.isNotEmpty()) File(n.audioPath).delete()
        val list = _notes.value.filterNot { it.id == id }
        save(list); _notes.value = list
    }
}
