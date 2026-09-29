package com.appsalad.recorder.audio

import android.content.Context
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * The always-on listener: a Vosk (Kaldi) small English model, run fully offline on the
 * phone. Nothing is sent anywhere until a command starts a recording.
 */
class Spotter private constructor(private val model: Model, private val sampleRate: Float) {
    private var rec = Recognizer(model, sampleRate)

    data class Heard(val text: String, val final: Boolean)

    fun feed(buf: ByteArray, len: Int): Heard {
        return if (rec.acceptWaveForm(buf, len)) Heard(JSONObject(rec.result).optString("text"), true)
        else Heard(JSONObject(rec.partialResult).optString("partial"), false)
    }

    /** Forget the current utterance, so text that already fired a command can't fire again. */
    fun reset() = rec.reset()

    fun close() { rec.close(); model.close() }

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
