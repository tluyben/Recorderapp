package com.appsalad.recorder.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class OpenRouterException(message: String) : IOException(message)

object OpenRouter {
    private const val BASE = "https://openrouter.ai/api/v1"

    private fun open(path: String, apiKey: String, method: String): HttpURLConnection =
        (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("HTTP-Referer", "https://appsalad.com/recorder")
            setRequestProperty("X-Title", "Recorder")
        }

    private fun HttpURLConnection.readBody(): String {
        val code = responseCode
        val body = (if (code in 200..299) inputStream else errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull()
            throw OpenRouterException("HTTP $code: ${msg?.takeIf { it.isNotBlank() } ?: body.take(300)}")
        }
        return body
    }

    /**
     * Speech-to-text through OpenRouter's /audio/transcriptions. Uses the OpenAI-style
     * multipart upload so a long recording streams from disk instead of being base64'd
     * into memory.
     */
    suspend fun transcribe(apiKey: String, model: String, audio: File, language: String = "", prompt: String = ""): String =
        withContext(Dispatchers.IO) {
            val boundary = "----recorder" + System.nanoTime()
            val conn = open("/audio/transcriptions", apiKey, "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setChunkedStreamingMode(64 * 1024)
            DataOutputStream(conn.outputStream.buffered()).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.write(value.toByteArray()); out.writeBytes("\r\n")
                }
                field("model", model)
                if (language.isNotBlank()) field("language", language)
                if (prompt.isNotBlank()) field("prompt", prompt)
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${audio.name}\"\r\n")
                out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                audio.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            val body = conn.readBody()
            JSONObject(body).optString("text").trim()
        }

    suspend fun chat(apiKey: String, model: String, system: String, question: String): String =
        withContext(Dispatchers.IO) {
            val conn = open("/chat/completions", apiKey, "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val messages = JSONArray()
            if (system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
            messages.put(JSONObject().put("role", "user").put("content", question))
            val req = JSONObject().put("model", model).put("messages", messages)
            conn.outputStream.use { it.write(req.toString().toByteArray()) }
            val body = JSONObject(conn.readBody())
            val choice = body.optJSONArray("choices")?.optJSONObject(0)
                ?: throw OpenRouterException("no answer: ${body.toString().take(300)}")
            val content = choice.optJSONObject("message")?.opt("content")
            when (content) {
                is String -> content.trim()
                // some providers return content parts
                is JSONArray -> (0 until content.length()).joinToString("") { content.optJSONObject(it)?.optString("text") ?: "" }.trim()
                else -> ""
            }
        }

    /** Checks the key; returns a short human description of it. */
    suspend fun checkKey(apiKey: String): String = withContext(Dispatchers.IO) {
        val d = JSONObject(open("/key", apiKey, "GET").readBody()).optJSONObject("data") ?: JSONObject()
        val usage = d.optDouble("usage", 0.0)
        val limit = d.opt("limit")
        buildString {
            append("Key OK")
            d.optString("label").takeIf { it.isNotBlank() && it != "null" }?.let { append(" ($it)") }
            append(" · used $%.2f".format(usage))
            if (limit is Number) append(" of $%.2f".format(limit.toDouble()))
        }
    }
}
