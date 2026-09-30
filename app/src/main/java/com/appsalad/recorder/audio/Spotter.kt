package com.appsalad.recorder.audio

import android.content.Context
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** libvosk exports a vocabulary lookup that the Java wrapper doesn't expose. */
interface VoskVocab : Library { fun vosk_model_find_word(model: Pointer, word: String): Int }

/**
 * The always-on listener: a Vosk (Kaldi) small English model, run fully offline on the
 * phone. Nothing is sent anywhere until a command starts a recording.
 */
class Spotter private constructor(private val model: Model, private val sampleRate: Float) {
    private var rec = Recognizer(model, sampleRate)
    // a second decoder that only knows the command phrases: it snaps an accented or
    // mumbled "take note" onto the phrase, where the open-vocabulary one may hear "take no"
    private var cmd = Recognizer(model, sampleRate, Commands.grammar())

    /** The phrases the command-only decoder listens for; changing them rebuilds its grammar. */
    var words: CommandWords = CommandWords()
        set(v) {
            if (v == field) return
            field = v
            cmd.setGrammar(Commands.grammar(v))
            cmd.reset()
        }

    /** Words of [cw] the model's vocabulary doesn't have — those can never be heard. */
    fun unknownWords(cw: CommandWords): List<String> =
        cw.words().filter { runCatching { vocab.vosk_model_find_word(model.pointer, it) < 0 }.getOrDefault(false) }
    private val vocab: VoskVocab by lazy { Native.load("vosk", VoskVocab::class.java) }

    data class Heard(val text: String, val final: Boolean)

    fun feed(buf: ByteArray, len: Int): Heard {
        return if (rec.acceptWaveForm(buf, len)) Heard(JSONObject(rec.result).optString("text"), true)
        else Heard(JSONObject(rec.partialResult).optString("partial"), false)
    }

    /** Feeds the command-only decoder; returns its text when an utterance ends, else null. */
    fun feedCommands(buf: ByteArray, len: Int): String? =
        if (cmd.acceptWaveForm(buf, len)) JSONObject(cmd.result).optString("text") else null

    /** Forget the current utterance, so text that already fired a command can't fire again. */
    fun reset() { rec.reset(); cmd.reset() }

    fun close() { rec.close(); cmd.close(); model.close() }

    companion object {
        private const val ASSET = "model-en-us"
        private const val MODEL_VERSION = "vosk-model-small-en-us-0.15"

        /** Copies the bundled model out of the APK once (Vosk needs real files). */
        fun install(context: Context): File {
            val dest = File(context.filesDir, ASSET)
            val marker = File(dest, ".installed")
            if (marker.exists() && marker.readText() == MODEL_VERSION) return dest
            dest.deleteRecursively()
            fun copy(path: String, to: File) {
                val kids = context.assets.list(path) ?: emptyArray()
                if (kids.isEmpty()) {
                    to.parentFile?.mkdirs()
                    context.assets.open(path).use { i -> to.outputStream().use { i.copyTo(it) } }
                } else kids.forEach { copy("$path/$it", File(to, it)) }
            }
            copy(ASSET, dest)
            marker.writeText(MODEL_VERSION)
            return dest
        }

        fun load(context: Context, sampleRate: Int): Spotter {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            return Spotter(Model(install(context).absolutePath), sampleRate.toFloat())
        }
    }
}
