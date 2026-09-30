package com.appsalad.recorder.net

import android.util.Base64OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * InferMux (infermux.net): one OpenAI-compatible endpoint in front of every model router.
 * It has no transcription endpoint, so speech-to-text is a chat completion with the
 * recording attached as input_audio — InferMux routes that to an audio-capable model.
 */
class InferMux(private val key: String, private val sttModel: String, private val chatModel: String) : AiClient {
    override val name = "InferMux"
    private val api = Endpoint(BASE, key)

    override suspend fun transcribe(audio: File, language: String, hint: String): String = withContext(Dispatchers.IO) {
        val instruction = buildString {
            append("Transcribe this audio recording verbatim")
            if (language.isNotBlank()) append(" (language: $language)") else append(", in the language that is spoken")
            append(". Reply with only the transcript: no preamble, no quotes, no notes or timestamps. ")
            append("If nothing intelligible is said, reply with nothing.")
            if (hint.isNotBlank()) append(" Context: $hint")
        }
        // stream the JSON so the WAV is base64'd straight from disk, never held in memory
        api.chatCompletion { out ->
            out.write(("{\"model\":${JSONObject.quote(sttModel)},\"temperature\":0,\"messages\":[{\"role\":\"user\",\"content\":[" +
                "{\"type\":\"text\",\"text\":${JSONObject.quote(instruction)}}," +
                "{\"type\":\"input_audio\",\"input_audio\":{\"format\":\"wav\",\"data\":\"").toByteArray())
            val b64 = Base64OutputStream(out, android.util.Base64.NO_WRAP or android.util.Base64.NO_CLOSE)
            audio.inputStream().use { it.copyTo(b64, 48 * 1024) }
            b64.close()
            out.write("\"}}]}]}".toByteArray())
        }
    }

    override suspend fun chat(system: String, question: String) = api.chat(chatModel, system, question)

    companion object {
        const val SITE = "https://infermux.net"
        const val BASE = "$SITE/api/v1"
        /** The "Recorder (Android)" login widget in InferMux (origin https://recorder.appsalad.com). */
        const val WIDGET_ID = "kwn5e1bqpcdzb718ihuntc"

        /** Checks the key; returns a short human description of it. */
        suspend fun checkKey(key: String): String = withContext(Dispatchers.IO) {
            val c = Endpoint(BASE, key).get("/credits")
            val k = c.optJSONObject("key")
            buildString {
                append("Key OK")
                k?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }?.let { append(" ($it)") }
                append(" · balance $%.2f".format(c.optDouble("balance_usd", 0.0)))
                k?.opt("spend_limit_usd")?.let { if (it is Number) append(" · key limit $%.2f".format(it.toDouble())) }
            }
        }
    }
}
