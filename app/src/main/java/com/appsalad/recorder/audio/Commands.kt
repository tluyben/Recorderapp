package com.appsalad.recorder.audio

import com.appsalad.recorder.data.NoteKind

enum class Command { TAKE_NOTE, QUESTION, STOP }

/**
 * Matches the offline recognizer's text against the spoken commands. Pure Kotlin so it
 * can be unit-tested. The recognizer emits lowercase words without punctuation.
 */
object Commands {
    private val takeNote = Regex("""\b(take|tape|taking|tak) (a |the )?(note|notes|nodes|knot|not|nope)\b""")
    // "question" only counts at the start of an utterance (after a pause), or as
    // "ask a question", so it does not fire on every conversation that uses the word
    private val question = Regex("""^(ok |okay |hey )?(a )?questions?\b|\bask (a )?question\b""")

    /** Vosk grammar for [Spotter]'s command-only decoder; everything else becomes [unk]. */
    const val GRAMMAR = """["take note", "take a note", "question", "ask a question", "stop stop", "[unk]"]"""

    /**
     * An utterance from the command-only decoder counts when it is (nearly) just the
     * command: said on its own, with a pause before and after. Background talk comes out
     * as mostly [unk], so it can't fire this.
     */
    fun fromGrammar(utterance: String?): Command? {
        val words = utterance?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
        val unk = words.count { it == "[unk]" }
        val said = words.filter { it != "[unk]" }.joinToString(" ")
        return when (said) {
            "take note", "take a note" -> if (unk <= 1) Command.TAKE_NOTE else null
            // "good question" decodes as "[unk] question", so a lone "question" must be alone
            "question" -> if (unk == 0) Command.QUESTION else null
            "ask a question" -> if (unk <= 1) Command.QUESTION else null
            else -> null
        }
    }
    private val stop = Regex("""\bstop(s|ped)? stop(s|ped)?\b""")

    fun whileIdle(heard: String): Command? {
        val t = heard.trim().lowercase()
        return when {
            takeNote.containsMatchIn(t) -> Command.TAKE_NOTE
            question.containsMatchIn(t) -> Command.QUESTION
            else -> null
        }
    }

    fun whileRecording(heard: String): Command? =
        if (stop.containsMatchIn(heard.trim().lowercase())) Command.STOP else null

    private val leadNote = Regex("""^[\s\p{Punct}]*(?:(?:ok(?:ay)?|so|hey)[\s\p{Punct}]+)?take[\s\p{Punct}]+(?:a[\s\p{Punct}]+)?notes?\b[\s\p{Punct}]*""", RegexOption.IGNORE_CASE)
    private val leadQuestion = Regex("""^[\s\p{Punct}]*(?:(?:ok(?:ay)?|so|hey)[\s\p{Punct}]+)?(?:ask[\s\p{Punct}]+(?:a[\s\p{Punct}]+)?)?question\b[\s\p{Punct}]*""", RegexOption.IGNORE_CASE)
    private val tailStop = Regex("""[\s,;:-]*\bstop[\s\p{Punct}]+stop\b[\s\p{Punct}]*$""", RegexOption.IGNORE_CASE)

    /**
     * Removes the command words the recording picked up: the trigger at the start (the
     * recording starts with a little pre-roll) and "stop stop" at the end.
     */
    fun cleanTranscript(kind: NoteKind, raw: String): String {
        var t = raw.trim()
        // the pre-roll can hold a word or two of whatever came just before the trigger
        val lead = if (kind == NoteKind.NOTE) leadNote else leadQuestion
        val window = t.take(60)
        val idx = if (kind == NoteKind.NOTE) Regex("""\btake[\s\p{Punct}]+(?:a[\s\p{Punct}]+)?notes?\b""", RegexOption.IGNORE_CASE).find(window)
        else Regex("""\bquestion\b""", RegexOption.IGNORE_CASE).find(window)
        if (idx != null && idx.range.first <= 25) t = t.substring(idx.range.first)
        t = t.replace(lead, "")
        repeat(2) { t = t.replace(tailStop, "") }
        t = t.trim()
        return t.replaceFirstChar { it.uppercaseChar() }
    }
}
