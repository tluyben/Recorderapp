package com.appsalad.recorder

import com.appsalad.recorder.audio.Command
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
}
