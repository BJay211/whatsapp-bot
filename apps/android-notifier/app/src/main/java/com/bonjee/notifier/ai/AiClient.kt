package com.bonjee.notifier.ai

import android.content.Context
import com.bonjee.notifier.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Direct LLM answers from the user's own provider.
 *
 * Groq is the default because it is cheap and fast, but the base URL is a field
 * on purpose: any OpenAI-compatible endpoint (Groq, OpenAI, OpenRouter, a local
 * Ollama, ...) works with the same code. The key lives in this app's private
 * preferences and is sent only to the base URL the user typed - never to n8n.
 *
 * No answer is invented: a failed call returns the provider's real HTTP code
 * and message, and the caller decides whether to fall back to the archive.
 */
class AiClient(context: Context) {

    private val prefs = Prefs.get(context.applicationContext)

    data class Message(val role: String, val content: String)

    data class Answer(
        val ok: Boolean,
        val code: Int,
        val text: String,
        val error: String,
        val model: String,
        val totalTokens: Int,
        /** True when the text was already delivered sentence by sentence. */
        val streamed: Boolean = false,
    ) {
        /** A short truthful line about where this answer came from. */
        fun sourceLine(): String = when {
            ok && totalTokens > 0 -> "AI · $model · $totalTokens tokens"
            ok -> "AI · $model"
            else -> "AI failed"
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun isConfigured(): Boolean = prefs.aiEnabled && canCall()

    /** Key and model are present, whether or not routing is switched on yet. */
    fun canCall(): Boolean = prefs.aiApiKey.isNotBlank() && prefs.aiModel.isNotBlank()

    fun baseUrl(): String = prefs.aiBaseUrl.trimEnd('/')

    /**
     * Sends the conversation and returns the assistant's reply.
     *
     * [history] should be oldest-first and already include the new question as
     * its last entry; the system prompt is prepended here so it always wins.
     */
    fun chat(history: List<Message>): Answer {
        if (!canCall()) {
            return Answer(false, 0, "", "AI has no API key or model.", "", 0)
        }
        val model = prefs.aiModel.trim()
        if (model.isEmpty()) {
            return Answer(false, 0, "", "No model name is set.", "", 0)
        }
        val url = baseUrl() + "/chat/completions"

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt()))
        history.takeLast(MAX_TURNS * 2).forEach { message ->
            if (message.content.isBlank()) return@forEach
            messages.put(JSONObject().put("role", message.role).put("content", message.content))
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.3)
            put("max_tokens", 1024)
        }

        return try {
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON))
                .header("Authorization", "Bearer " + prefs.aiApiKey.trim())
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Answer(
                        ok = false,
                        code = response.code,
                        text = "",
                        error = describeError(response.code, body, model),
                        model = model,
                        totalTokens = 0,
                    )
                }
                parseAnswer(body, model)
            }
        } catch (e: Exception) {
            Answer(false, 0, "", connectionError(e), model, 0)
        }
    }

    /**
     * Streams the answer and hands complete sentences to [onSentence] while the
     * provider is still generating. The full text is returned as usual, so the
     * screen and the follow-up context stay complete.
     *
     * If the provider ignores `stream=true` or the stream breaks before any
     * prose arrives, the normal call is used instead. A partial stream is
     * reported as a failure with the text received so far, not as success.
     */
    fun chatStreaming(
        history: List<Message>,
        onSentence: (String) -> Unit,
    ): Answer {
        if (!canCall()) {
            return Answer(false, 0, "", "AI has no API key or model.", "", 0)
        }
        val model = prefs.aiModel.trim()
        val url = baseUrl() + "/chat/completions"
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt()))
        history.takeLast(MAX_TURNS * 2).forEach { message ->
            if (message.content.isBlank()) return@forEach
            messages.put(JSONObject().put("role", message.role).put("content", message.content))
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.3)
            put("max_tokens", 1024)
            put("stream", true)
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON))
            .header("Authorization", "Bearer " + prefs.aiApiKey.trim())
            .header("Accept", "text/event-stream")
            .build()

        val full = StringBuilder()
        var delivered = false
        val buffer = com.bonjee.notifier.voice.SentenceBuffer { sentence ->
            delivered = true
            onSentence(sentence)
        }

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return Answer(
                        false,
                        response.code,
                        "",
                        describeError(response.code, body, model),
                        model,
                        0,
                    )
                }

                val source = response.body?.source()
                    ?: return Answer(false, response.code, "", "The provider sent no stream.", model, 0)
                var tokens = 0
                var served = model
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:", ignoreCase = true)) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    val json = try {
                        JSONObject(data)
                    } catch (t: Throwable) {
                        continue
                    }
                    served = json.optString("model").ifBlank { served }
                    json.optJSONObject("usage")?.optInt("total_tokens", 0)?.let {
                        if (it > 0) tokens = it
                    }
                    val choices = json.optJSONArray("choices") ?: continue
                    for (i in 0 until choices.length()) {
                        val delta = choices.optJSONObject(i)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                            .orEmpty()
                        if (delta.isEmpty()) continue
                        full.append(delta)
                        buffer.append(delta)
                    }
                }
                buffer.finish()

                val text = full.toString().trim()
                if (text.isEmpty()) {
                    // The endpoint may not implement SSE at all. The ordinary
                    // call is the reliable fallback.
                    return@use chat(history)
                }
                Answer(true, 200, text, "", served, tokens, streamed = delivered)
            }
        } catch (e: Exception) {
            val partial = full.toString().trim()
            if (partial.isNotEmpty()) {
                Answer(
                    false,
                    0,
                    partial,
                    "The stream stopped early: ${e.message ?: e.javaClass.simpleName}",
                    model,
                    0,
                    streamed = delivered,
                )
            } else {
                Answer(false, 0, "", connectionError(e), model, 0)
            }
        }
    }

    /** Asks for the model ids the key can actually use. */
    fun listModels(): Pair<List<String>, String> {
        if (prefs.aiApiKey.isBlank()) return emptyList<String>() to "Add an API key first."
        val url = baseUrl() + "/models"
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer " + prefs.aiApiKey.trim())
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return emptyList<String>() to describeError(
                        response.code, body, prefs.aiModel,
                    )
                }
                val array = JSONObject(body).optJSONArray("data") ?: JSONArray()
                val ids = ArrayList<String>(array.length())
                for (i in 0 until array.length()) {
                    val id = array.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank()) ids.add(id)
                }
                ids.sorted() to if (ids.isEmpty()) "The provider returned no models." else ""
            }
        } catch (e: Exception) {
            emptyList<String>() to connectionError(e)
        }
    }

    /** One cheap round trip, used by the settings screen's TEST button. */
    fun test(): Answer = chat(listOf(Message("user", "Reply with exactly: OK")))

    private fun parseAnswer(body: String, model: String): Answer {
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return Answer(false, 200, "", "The provider sent a response I could not read.", model, 0)
        }
        val choices = json.optJSONArray("choices")
        val text = choices?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) {
            val reason = json.optString("error").ifBlank { "The provider returned an empty answer." }
            return Answer(false, 200, "", reason, model, 0)
        }
        val tokens = json.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0
        val served = json.optString("model").ifBlank { model }
        return Answer(true, 200, text, "", served, tokens)
    }

    private fun describeError(code: Int, body: String, model: String): String {
        val providerMessage = try {
            val json = JSONObject(body.trim())
            val error = json.optJSONObject("error")
            error?.optString("message").orEmpty()
                .ifBlank { json.optString("message") }
                .ifBlank { body.take(300) }
        } catch (t: Throwable) {
            body.take(300).ifBlank { "(empty body)" }
        }
        return when (code) {
            401, 403 -> "The API key was rejected (HTTP $code). Check the key and that it " +
                "belongs to ${baseUrl()}.\n$providerMessage"
            404 -> "HTTP 404. Either the base URL is wrong or the model \"$model\" is not " +
                "available on this account.\n$providerMessage"
            429 -> "Rate limited by the provider (HTTP 429). Wait a moment and try again.\n" +
                providerMessage
            in 500..599 -> "The provider had a server error (HTTP $code). Try again.\n" +
                providerMessage
            else -> "HTTP $code from ${baseUrl()}\n$providerMessage"
        }
    }

    private fun connectionError(e: Exception): String {
        val raw = (e.message ?: e.javaClass.simpleName).lowercase(Locale.getDefault())
        return when {
            raw.contains("timeout") || raw.contains("timed out") ->
                "The AI provider did not answer in time. Try again, or pick a smaller model."
            raw.contains("unable to resolve") || raw.contains("unknown host") ->
                "The AI address does not resolve. Check the base URL and the phone's internet."
            raw.contains("ssl") || raw.contains("certificate") ->
                "TLS failed for ${baseUrl()}. The base URL must be https."
            else -> "Could not reach the AI provider: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun systemPrompt(): String {
        val today = SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault()).format(Date())
        return """
            You are JARVIS, a concise assistant running inside the user's Android app.
            Today is $today. The user's name is Bonjee.

            Answer the question directly and briefly. Prefer 2-5 sentences unless the
            user asks for detail. Use plain text: no markdown tables, no code fences
            unless code is requested.

            You cannot control the phone or the laptop yourself. Those actions are
            handled by the app before your answer, so never claim you opened an app,
            sent a message, ran a command or changed a setting. If the user asks for
            one of those, tell them to phrase it as a command in the app.
        """.trimIndent()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Six user/assistant pairs is enough context without bloating the request. */
        const val MAX_TURNS = 6
    }
}
