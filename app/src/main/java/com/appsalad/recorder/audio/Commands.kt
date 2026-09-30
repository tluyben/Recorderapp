package com.appsalad.recorder.audio

import com.appsalad.recorder.data.NoteKind

enum class Command { TAKE_NOTE, QUESTION, STOP }

/**
 * The spoken command phrases, as set in Settings (normalised: lowercase, no punctuation,
 * single spaces). The defaults are "take note", "question" and "stop stop".
 */
data class CommandWords(val note: String = DEFAULT_NOTE, val question: String = DEFAULT_QUESTION, val stop: String = DEFAULT_STOP) {
    companion object {
        const val DEFAULT_NOTE = "take note"
        const val DEFAULT_QUESTION = "question"
        const val DEFAULT_STOP = "stop stop"

        fun normalize(s: String): String =
            s.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]+"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

        /** Builds from user input; a blank phrase falls back to its default. */
        fun of(note: String, question: String, stop: String) = CommandWords(
            normalize(note).ifEmpty { DEFAULT_NOTE },
            normalize(question).ifEmpty { DEFAULT_QUESTION },
            normalize(stop).ifEmpty { DEFAULT_STOP },
        )

        /** “Take note” — how a phrase is shown in the UI. */
        fun quote(p: String) = "“" + p.replaceFirstChar { it.uppercaseChar() } + "”"
    }

    val all: List<String> get() = listOf(note, question, stop)
    fun words(): Set<String> = all.flatMap { it.split(" ") }.toSet()
}

/**
 * Matches the offline recognizer's text against the spoken commands. Pure Kotlin so it
 * can be unit-tested. The recognizer emits lowercase words without punctuation.
 *
 * Rules for any phrase: a phrase of two or more words counts anywhere in what was heard.
 * A single word only counts at the start of an utterance (after a pause) as a trigger,
 * or as a whole utterance as the stop word — so everyday use of the word doesn't fire.
 * The three defaults also accept the mishearings the small model tends to produce.
 */
object Commands {
    private val defaultNote = Regex("""\b(take|tape|taking|tak) (a |the )?(note|notes|nodes|knot|not|nope)\b""")
    private val defaultQuestion = Regex("""^(ok |okay |hey )?(a )?questions?\b|\bask (a )?question\b""")
    private val defaultStop = Regex("""\bstop(s|ped)? stop(s|ped)?\b""")

    private fun esc(p: String) = p.split(" ").joinToString(" ") { Regex.escape(it) }
    private fun isMulti(p: String) = p.contains(' ')

    private fun triggerRegex(p: String, default: String, defaultRegex: Regex): Regex = when {
        p == default -> defaultRegex
        isMulti(p) -> Regex("""\b${esc(p)}\b""")
        else -> Regex("""^(ok |okay |hey )?${esc(p)}\b""")
    }

    fun whileIdle(heard: String, cw: CommandWords = CommandWords()): Command? {
        val t = heard.trim().lowercase()
        if (t.isEmpty()) return null
        return when {
            triggerRegex(cw.note, CommandWords.DEFAULT_NOTE, defaultNote).containsMatchIn(t) -> Command.TAKE_NOTE
            triggerRegex(cw.question, CommandWords.DEFAULT_QUESTION, defaultQuestion).containsMatchIn(t) -> Command.QUESTION
            else -> null
        }
    }

    fun whileRecording(heard: String, cw: CommandWords = CommandWords()): Command? {
        val t = heard.trim().lowercase()
        val hit = when {
            cw.stop == CommandWords.DEFAULT_STOP -> defaultStop.containsMatchIn(t)
            isMulti(cw.stop) -> Regex("""\b${esc(cw.stop)}\b""").containsMatchIn(t)
            else -> t == cw.stop
        }
        return if (hit) Command.STOP else null
    }

    /** Vosk grammar for [Spotter]'s command-only decoder; everything else becomes [unk]. */
    fun grammar(cw: CommandWords = CommandWords()): String {
        val phrases = cw.all.toMutableList()
        if (cw.note == CommandWords.DEFAULT_NOTE) phrases += "take a note"
        if (cw.question == CommandWords.DEFAULT_QUESTION) phrases += "ask a question"
        return (phrases.distinct() + "[unk]").joinToString(", ", "[", "]") { "\"" + it.replace("\"", "") + "\"" }
    }

    /**
     * An utterance from the command-only decoder counts when it is (nearly) just the
     * command: said on its own, with a pause before and after. Background talk comes out
     * as mostly [unk], so it can't fire this.
     */
    fun fromGrammar(utterance: String?, cw: CommandWords = CommandWords()): Command? {
        val words = utterance?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
        val unk = words.count { it == "[unk]" }
        val said = words.filter { it != "[unk]" }.joinToString(" ")
        if (said.isEmpty()) return null
        // a single word must be alone ("good question" decodes as "[unk] question")
        fun ok(p: String) = if (isMulti(p)) unk <= 1 else unk == 0
        return when {
            said == cw.note && ok(cw.note) -> Command.TAKE_NOTE
            cw.note == CommandWords.DEFAULT_NOTE && said == "take a note" && unk <= 1 -> Command.TAKE_NOTE
            said == cw.question && ok(cw.question) -> Command.QUESTION
            cw.question == CommandWords.DEFAULT_QUESTION && said == "ask a question" && unk <= 1 -> Command.QUESTION
            else -> null
        }
    }

    /** The phrase as a regex over transcribed text (any punctuation between words). */
    private fun spoken(p: String) = p.split(" ").joinToString("""[\s\p{Punct}]+""") { Regex.escape(it) }

    /**
     * Removes the command words the recording picked up: the trigger at the start (the
     * recording starts with a little pre-roll) and the stop phrase at the end.
     */
    fun cleanTranscript(kind: NoteKind, raw: String, cw: CommandWords = CommandWords()): String {
        var t = raw.trim()
        val trigger = when {
            kind == NoteKind.NOTE && cw.note == CommandWords.DEFAULT_NOTE -> """take[\s\p{Punct}]+(?:a[\s\p{Punct}]+)?notes?"""
            kind == NoteKind.NOTE -> spoken(cw.note)
            cw.question == CommandWords.DEFAULT_QUESTION -> """(?:ask[\s\p{Punct}]+(?:a[\s\p{Punct}]+)?)?question"""
            else -> spoken(cw.question)
        }
        // the pre-roll can hold a word or two of whatever came just before the trigger
        val found = Regex("""\b$trigger\b""", RegexOption.IGNORE_CASE).find(t.take(60))
        if (found != null && found.range.first <= 25) t = t.substring(found.range.first)
        t = t.replace(Regex("""^[\s\p{Punct}]*(?:(?:ok(?:ay)?|so|hey)[\s\p{Punct}]+)?$trigger\b[\s\p{Punct}]*""", RegexOption.IGNORE_CASE), "")
        val tail = Regex("""[\s,;:-]*\b${spoken(cw.stop)}\b[\s\p{Punct}]*$""", RegexOption.IGNORE_CASE)
        repeat(2) { t = t.replace(tail, "") }
        return t.trim().replaceFirstChar { it.uppercaseChar() }
    }
}
