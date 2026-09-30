package com.appsalad.recorder.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File

/** OpenRouter: /audio/transcriptions for speech, /chat/completions for answers. */
class OpenRouter(private val key: String, private val sttModel: String, private val chatModel: String) : AiClient {
    override val name = "OpenRouter"
    private val api = Endpoint(BASE, key)

    /**
     * Uses the OpenAI-style multipart upload, so a long recording streams from disk
     * instead of being base64'd into memory.
     */
    override suspend fun transcribe(audio: File, language: String, hint: String): String = withContext(Dispatchers.IO) {
        val boundary = "----recorder" + System.nanoTime()
        val conn = api.open("/audio/transcriptions", "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setChunkedStreamingMode(64 * 1024)
        DataOutputStream(conn.outputStream.buffered()).use { out ->
            fun field(name: String, value: String) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
                out.write(value.toByteArray()); out.writeBytes("\r\n")
            }
            field("model", sttModel)
            if (language.isNotBlank()) field("language", language)
            if (hint.isNotBlank()) field("prompt", hint)
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${audio.name}\"\r\n")
            out.writeBytes("Content-Type: audio/wav\r\n\r\n")
            audio.inputStream().use { it.copyTo(out, 64 * 1024) }
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        JSONObject(api.read(conn)).optString("text").trim()
    }

    override suspend fun chat(system: String, question: String) = api.chat(chatModel, system, question)

    companion object {
        const val BASE = "https://openrouter.ai/api/v1"

        /** Checks the key; returns a short human description of it. */
        suspend fun checkKey(key: String): String = withContext(Dispatchers.IO) {
            val d = Endpoint(BASE, key).get("/key").optJSONObject("data") ?: JSONObject()
            val limit = d.opt("limit")
            buildString {
                append("Key OK")
                d.optString("label").takeIf { it.isNotBlank() && it != "null" }?.let { append(" ($it)") }
                append(" · used $%.2f".format(d.optDouble("usage", 0.0)))
                if (limit is Number) append(" of $%.2f".format(limit.toDouble()))
            }
        }
    }
}
