package com.appsalad.recorder.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class ApiException(message: String, val status: Int = 0) : IOException(message)

/** Speech-to-text and chat, whichever service is behind it. */
interface AiClient {
    /** Short name for messages ("InferMux", "OpenRouter"). */
    val name: String
    suspend fun transcribe(audio: java.io.File, language: String, hint: String): String
    suspend fun chat(system: String, question: String): String
}

/** An OpenAI-compatible HTTP endpoint (OpenRouter and InferMux both are). */
internal class Endpoint(private val base: String, private val key: String) {
    fun open(path: String, method: String): HttpURLConnection =
        (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("HTTP-Referer", "https://appsalad.com/recorder")
            setRequestProperty("X-Title", "Recorder")
        }

    fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            // OpenAI envelope {"error":{"message"}} or a flat {"error":"…"}
            val msg = runCatching {
                val e = JSONObject(body).get("error")
                if (e is JSONObject) e.optString("message") else e.toString()
            }.getOrNull()
            throw ApiException("HTTP $code: ${msg?.takeIf { it.isNotBlank() } ?: body.take(300)}", code)
        }
        return body
    }

    fun get(path: String): JSONObject = JSONObject(read(open(path, "GET")))

    /** POST /chat/completions with the body written by [writeBody]; returns the answer text. */
    fun chatCompletion(writeBody: (OutputStream) -> Unit): String {
        val conn = open("/chat/completions", "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setChunkedStreamingMode(64 * 1024)
        conn.outputStream.buffered(64 * 1024).use(writeBody)
        val body = JSONObject(read(conn))
        val choice = body.optJSONArray("choices")?.optJSONObject(0)
            ?: throw ApiException("no answer: ${body.toString().take(300)}")
        return when (val content = choice.optJSONObject("message")?.opt("content")) {
            is String -> content.trim()
            // some providers return content parts
            is JSONArray -> (0 until content.length()).joinToString("") { content.optJSONObject(it)?.optString("text") ?: "" }.trim()
            else -> ""
        }
    }

    suspend fun chat(model: String, system: String, question: String): String = withContext(Dispatchers.IO) {
        val messages = JSONArray()
        if (system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
        messages.put(JSONObject().put("role", "user").put("content", question))
        val req = JSONObject().put("model", model).put("messages", messages).toString().toByteArray()
        chatCompletion { it.write(req) }
    }
}
