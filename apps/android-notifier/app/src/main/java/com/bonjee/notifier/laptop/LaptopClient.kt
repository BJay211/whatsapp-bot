package com.bonjee.notifier.laptop

import android.content.Context
import com.bonjee.notifier.BuildConfig
import com.bonjee.notifier.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Talks to the JARVIS laptop agent on the local network.
 *
 * The agent is a small PowerShell HTTP server the user runs on their own
 * computer (see `laptop-agent/`). There is no cloud hop: the request goes from
 * the phone straight to the laptop's LAN address, so it keeps working when n8n
 * is down.
 *
 * Honesty rules followed here:
 *  - a connection failure is reported as a connection failure, never as success;
 *  - a non-2xx response keeps the HTTP code and the agent's message;
 *  - an exit code other than 0 is a failure even though HTTP returned 200;
 *  - the token is never logged or included in an error message.
 */
class LaptopClient(context: Context) {

    private val app = context.applicationContext
    private val prefs = Prefs.get(app)

    data class AgentCommand(
        val name: String,
        val description: String,
        val args: List<String>,
        val aliases: List<String>,
        val preview: String,
    )

    data class AgentInfo(
        val name: String,
        val version: String,
        val mode: String,
        val host: String,
        val workingDirectory: String,
        val commands: List<AgentCommand>,
    ) {
        val freeMode: Boolean get() = mode.equals("free", ignoreCase = true)
    }

    data class Health(
        val ok: Boolean,
        val code: Int,
        val info: AgentInfo?,
        val message: String,
    )

    data class Run(
        val ok: Boolean,
        val httpCode: Int,
        val requestId: String,
        val command: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val timedOut: Boolean,
        val message: String,
    ) {
        /** What the user should read. Never says "done" unless it really was. */
        fun summary(): String = buildString {
            when {
                timedOut -> append("Timed out on the laptop.")
                ok -> append("Done (exit 0) in ${durationMs} ms.")
                httpCode == 0 -> append("Could not reach the laptop.")
                exitCode != 0 -> append("Failed (exit $exitCode) in ${durationMs} ms.")
                else -> append(message)
            }
            if (stdout.isNotBlank()) {
                append("\n\n")
                append(stdout.trim())
            }
            if (stderr.isNotBlank()) {
                append("\n\nstderr:\n")
                append(stderr.trim())
            }
            if (httpCode != 0 && !ok && message.isNotBlank() && stdout.isBlank()) {
                append("\n\n")
                append(message)
            }
        }
    }

    // A wrong address should fail fast; a long command may take a while.
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(200, TimeUnit.SECONDS)
        .callTimeout(210, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun baseUrl(): String = prefs.laptopBaseUrl()

    fun isConfigured(): Boolean = prefs.laptopEnabled && prefs.laptopConfigured()

    private fun tokenHeader(): Map<String, String> {
        val token = prefs.laptopToken.trim()
        return if (token.isEmpty()) emptyMap() else mapOf(HEADER_TOKEN to token)
    }

    /** Unauthenticated liveness probe, used by discovery and "is it on?". */
    fun ping(base: String = baseUrl()): Pair<Boolean, String> {
        if (base.isBlank()) return false to "No laptop address is set."
        val url = base.trimEnd('/') + "/ping"
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val name = try {
                        JSONObject(body).optString("name")
                    } catch (t: Throwable) {
                        ""
                    }
                    true to if (name.isBlank()) "Agent answered." else "$name answered."
                } else {
                    false to "HTTP ${response.code} from $url"
                }
            }
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    fun health(): Health {
        val base = baseUrl()
        if (base.isBlank()) {
            return Health(false, 0, null, "Set the laptop address first.")
        }
        if (prefs.laptopToken.isBlank()) {
            return Health(false, 0, null, "Set the laptop token from config.json first.")
        }
        val url = "$base/health"
        return try {
            val builder = Request.Builder().url(url).get()
            tokenHeader().forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 401 -> Health(
                        false, 401, null,
                        "The token does not match the agent's config.json.",
                    )
                    !response.isSuccessful -> Health(
                        false, response.code, null,
                        "HTTP ${response.code} from $url\n${errorMessage(body)}",
                    )
                    else -> {
                        val info = parseInfo(body)
                        if (info == null) {
                            Health(false, response.code, null, "The agent answered with unexpected JSON.")
                        } else {
                            Health(true, response.code, info, "Connected to ${info.name}.")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Health(false, 0, null, connectionHint(e))
        }
    }

    fun execute(parsed: LaptopCommand.Parsed): Run {
        val base = baseUrl()
        val requestId = UUID.randomUUID().toString()
        if (base.isBlank()) {
            return failure(requestId, parsed, "No laptop address is set.")
        }
        if (prefs.laptopToken.isBlank()) {
            return failure(requestId, parsed, "No laptop token is set.")
        }

        val argsJson = JSONObject()
        parsed.args.forEach { (key, value) -> argsJson.put(key, value) }

        val payload = JSONObject().apply {
            put("request_id", requestId)
            put("command", parsed.name)
            put("raw", parsed.raw)
            put("args", argsJson)
            put("client", "android")
            put("app_version", BuildConfig.VERSION_NAME)
        }

        val url = "$base/execute"
        return try {
            val body = payload.toString().toRequestBody(JSON)
            val builder = Request.Builder().url(url).post(body)
            tokenHeader().forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.code == 401 -> failure(
                        requestId, parsed,
                        "The token does not match the agent's config.json.",
                    )
                    response.code == 403 -> failure(
                        requestId, parsed,
                        "The agent refused that command: ${errorMessage(text)}",
                    )
                    response.code == 404 -> failure(
                        requestId, parsed,
                        "The agent is reachable but has no route at /execute. " +
                            "Check that you are running laptop-agent/agent.ps1, not another server.",
                    )
                    !response.isSuccessful -> failure(
                        requestId, parsed,
                        "HTTP ${response.code} from $url\n${errorMessage(text)}",
                    )
                    else -> parseRun(text, requestId, parsed)
                }
            }
        } catch (e: Exception) {
            failure(requestId, parsed, connectionHint(e))
        }
    }

    private fun parseRun(body: String, requestId: String, parsed: LaptopCommand.Parsed): Run {
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return failure(requestId, parsed, "The agent sent a response I could not read.")
        }
        val exit = json.optInt("exit_code", -1)
        val timedOut = json.optBoolean("timed_out", false)
        val executionOk = json.optBoolean("ok", false) && exit == 0 && !timedOut
        val message = json.optString("error").ifBlank {
            if (executionOk) "" else "The laptop reported a failure."
        }
        return Run(
            ok = executionOk,
            httpCode = 200,
            requestId = json.optString("request_id").ifBlank { requestId },
            command = parsed.raw,
            exitCode = exit,
            stdout = json.optString("stdout"),
            stderr = json.optString("stderr"),
            durationMs = json.optLong("duration_ms", 0L),
            timedOut = timedOut,
            message = message,
        )
    }

    private fun failure(requestId: String, parsed: LaptopCommand.Parsed, message: String): Run =
        Run(
            ok = false,
            httpCode = 0,
            requestId = requestId,
            command = parsed.raw,
            exitCode = -1,
            stdout = "",
            stderr = "",
            durationMs = 0L,
            timedOut = false,
            message = message,
        )

    private fun connectionHint(e: Exception): String {
        val raw = (e.message ?: e.javaClass.simpleName).lowercase(Locale.getDefault())
        val hint = when {
            raw.contains("timeout") || raw.contains("timed out") ->
                "The laptop did not answer in time. Is the agent window still running?"
            raw.contains("refused") ->
                "Connection refused. The agent is not running, or the port is wrong."
            raw.contains("unable to resolve") || raw.contains("unknown host") ->
                "That address does not resolve. Use the laptop's LAN IP, not a name."
            raw.contains("network is unreachable") || raw.contains("no route") ->
                "The phone cannot reach that network. Phone and laptop must be on the same Wi-Fi."
            else -> e.message ?: e.javaClass.simpleName
        }
        return "$hint\n\nAddress: ${baseUrl()}"
    }

    private fun errorMessage(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return "(empty response)"
        return try {
            val json = JSONObject(trimmed)
            json.optString("error").ifBlank { json.optString("message").ifBlank { trimmed.take(400) } }
        } catch (t: Throwable) {
            trimmed.take(400)
        }
    }

    private fun parseInfo(body: String): AgentInfo? = try {
        val json = JSONObject(body)
        val array = json.optJSONArray("commands") ?: JSONArray()
        val commands = ArrayList<AgentCommand>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val argArray = item.optJSONArray("args") ?: JSONArray()
            val args = ArrayList<String>(argArray.length())
            for (j in 0 until argArray.length()) {
                val name = argArray.optString(j)
                if (name.isNotBlank()) args.add(name)
            }
            val aliasArray = item.optJSONArray("aliases") ?: JSONArray()
            val aliases = ArrayList<String>(aliasArray.length())
            for (j in 0 until aliasArray.length()) {
                val alias = aliasArray.optString(j)
                if (alias.isNotBlank()) aliases.add(alias)
            }
            commands.add(
                AgentCommand(
                    name = item.optString("name"),
                    description = item.optString("description"),
                    args = args,
                    aliases = aliases,
                    preview = item.optString("preview"),
                ),
            )
        }
        AgentInfo(
            name = json.optString("name").ifBlank { "laptop agent" },
            version = json.optString("version"),
            mode = json.optString("mode"),
            host = json.optString("host"),
            workingDirectory = json.optString("working_directory"),
            commands = commands,
        )
    } catch (t: Throwable) {
        null
    }

    companion object {
        const val HEADER_TOKEN = "X-JARVIS-Token"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
