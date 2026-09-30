package com.appsalad.recorder

import com.appsalad.recorder.audio.Command
import com.appsalad.recorder.audio.CommandWords
import com.appsalad.recorder.audio.Commands
import com.appsalad.recorder.data.NoteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandsTest {
    @Test fun idleTriggers() {
        assertEquals(Command.TAKE_NOTE, Commands.whileIdle("take note"))
        assertEquals(Command.TAKE_NOTE, Commands.whileIdle("okay take a note"))
        assertEquals(Command.QUESTION, Commands.whileIdle("question"))
        assertEquals(Command.QUESTION, Commands.whileIdle("hey question what is"))
        assertEquals(Command.QUESTION, Commands.whileIdle("let me ask a question"))
        assertNull(Commands.whileIdle("that is a good question i think"))
        assertNull(Commands.whileIdle("we took notes yesterday"))
        assertNull(Commands.whileIdle(""))
    }

    @Test fun accentVariants() {
        assertEquals(Command.TAKE_NOTE, Commands.whileIdle("take no"+"t"))
        assertEquals(Command.TAKE_NOTE, Commands.whileIdle("tape note"))
        assertEquals(Command.QUESTION, Commands.whileIdle("questions what time"))
        assertNull(Commands.whileIdle("good question"))
        assertNull(Commands.whileIdle("i was thinking about that question"))
    }

    @Test fun grammarUtterances() {
        assertEquals(Command.TAKE_NOTE, Commands.fromGrammar("take note"))
        assertEquals(Command.TAKE_NOTE, Commands.fromGrammar("[unk] take a note"))
        assertEquals(Command.QUESTION, Commands.fromGrammar("question"))
        assertNull(Commands.fromGrammar("[unk] [unk] question"))
        assertNull(Commands.fromGrammar("[unk] question"))
        assertNull(Commands.fromGrammar("[unk] take note [unk] [unk]"))
        assertNull(Commands.fromGrammar(""))
        assertNull(Commands.fromGrammar(null))
    }

    @Test fun stopNeedsTwo() {
        assertEquals(Command.STOP, Commands.whileRecording("and that is it stop stop"))
        assertEquals(Command.STOP, Commands.whileRecording("stop stopped"))
        assertNull(Commands.whileRecording("please stop the car"))
        assertNull(Commands.whileRecording("stop and go stop"))
    }

    @Test fun cleansCommandWords() {
        assertEquals("Buy milk and eggs.", Commands.cleanTranscript(NoteKind.NOTE, "Take note. Buy milk and eggs. Stop, stop."))
        assertEquals("Call Anna tomorrow", Commands.cleanTranscript(NoteKind.NOTE, "yeah so, take a note: call Anna tomorrow stop stop"))
        assertEquals("What is the capital of Peru?", Commands.cleanTranscript(NoteKind.QUESTION, "Question. What is the capital of Peru? Stop. Stop."))
        // real gpt-4o-mini-transcribe output for a TTS clip of the full command
        assertEquals("Remember to buy milk, eggs, and coffee beans on Saturday.", Commands.cleanTranscript(NoteKind.NOTE,
            "Take note: remember to buy milk, eggs, and coffee beans on Saturday. Stop. Stop."))
        assertEquals("Nothing to strip here", Commands.cleanTranscript(NoteKind.NOTE, "nothing to strip here"))
    }

    private val custom = CommandWords.of("Memo, please!", "Jarvis", "over and out")

    @Test fun normalises() {
        assertEquals(CommandWords("memo please", "jarvis", "over and out"), custom)
        assertEquals(CommandWords(), CommandWords.of("  ", "", "!!"))
        assertEquals("“Take note”", CommandWords.quote("take note"))
    }

    @Test fun customTriggers() {
        assertEquals(Command.TAKE_NOTE, Commands.whileIdle("okay memo please", custom))
        assertEquals(Command.QUESTION, Commands.whileIdle("jarvis what is the time", custom))
        assertNull(Commands.whileIdle("i talked to jarvis", custom))    // single word: start of utterance only
        assertNull(Commands.whileIdle("take note", custom))             // the defaults are off now
        assertNull(Commands.whileIdle("question", custom))
    }

    @Test fun customStop() {
        assertEquals(Command.STOP, Commands.whileRecording("that is all over and out", custom))
        assertNull(Commands.whileRecording("stop stop", custom))
        val oneWord = CommandWords.of("take note", "question", "done")
        assertEquals(Command.STOP, Commands.whileRecording("done", oneWord))
        assertNull(Commands.whileRecording("i am done with it", oneWord)) // single word: must be said on its own
    }

    @Test fun customGrammar() {
        assertEquals("""["memo please", "jarvis", "over and out", "[unk]"]""", Commands.grammar(custom))
        assertEquals("""["take note", "question", "stop stop", "take a note", "ask a question", "[unk]"]""", Commands.grammar())
        assertEquals(Command.TAKE_NOTE, Commands.fromGrammar("[unk] memo please", custom))
        assertEquals(Command.QUESTION, Commands.fromGrammar("jarvis", custom))
        assertNull(Commands.fromGrammar("[unk] jarvis", custom))
        assertNull(Commands.fromGrammar("take note", custom))
    }

    @Test fun customClean() {
        assertEquals("Buy milk.", Commands.cleanTranscript(NoteKind.NOTE, "Memo, please. Buy milk. Over and out.", custom))
        assertEquals("What time is it?", Commands.cleanTranscript(NoteKind.QUESTION, "Jarvis, what time is it? Over and out!", custom))
    }
}
