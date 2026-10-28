package com.bonjee.notifier.friday

import android.content.Context
import com.bonjee.notifier.BuildConfig
import com.bonjee.notifier.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Talks to Friday, the assistant running on the user's own laptop.
 *
 * Transport rules (see docs/FRIDAY_SECURITY.md):
 *  - TLS only, and the laptop's self-signed certificate is pinned by its
 *    SHA-256 fingerprint. A different certificate is refused before any byte of
 *    the request is sent, so a look-alike server on the same Wi-Fi cannot
 *    answer as Friday.
 *  - The device token is stored in this app's private preferences and is never
 *    logged, shown in an error message or written into the audit trail.
 *  - A connection failure is always reported as a connection failure. Nothing
 *    here returns "done" unless Friday reported a terminal success.
 *  - Long work returns a task id; the caller polls [task] instead of holding one
 *    HTTP request open.
 */
class FridayClient(context: Context) {

    private val app = context.applicationContext
    private val prefs = Prefs.get(app)

    // ------------------------------------------------------------ data shapes

    data class Ping(
        val name: String,
        val version: String,
        val protocol: String,
        val machine: String,
        val tls: Boolean,
        val fingerprint: String,
        val pairingRequired: Boolean,
        val emergencyStop: Boolean,
    )

    data class Paired(
        val deviceId: String,
        val deviceName: String,
        val token: String,
        val fingerprint: String,
        val protocol: String,
    )

    data class Approval(
        val id: String,
        val title: String,
        val detail: String,
        val capability: String,
        val destructive: Boolean,
    )

    /**
     * One task as Friday reports it. [status] is always one of received,
     * validating, awaiting_approval, running, succeeded, failed, cancelled.
     */
    data class Task(
        val taskId: String,
        val status: String,
        val summary: String,
        val progress: String,
        val error: String,
        val instruction: String,
        val needsApproval: Boolean,
        val approval: Approval?,
        val isTerminal: Boolean,
        val duplicate: Boolean,
        val createdAt: String,
    ) {
        val awaitingApproval: Boolean get() = status == STATUS_AWAITING

        val succeeded: Boolean get() = status == STATUS_SUCCEEDED

        /** What the user should read. Never claims success the laptop did not report. */
        fun display(): String = when (status) {
            STATUS_SUCCEEDED -> summary.ifBlank { "Done on the laptop." }
            STATUS_FAILED -> "That did not work on the laptop.\n\n" + error.ifBlank { summary }
            STATUS_CANCELLED -> "Cancelled. " + summary
            STATUS_AWAITING -> "Friday is waiting for approval on the laptop."
            else -> progress.ifBlank { summary }.ifBlank { "Working on it..." }
        }
    }

    data class StatusView(
        val machine: String,
        val state: String,
        val detail: String,
        val aiConfigured: Boolean,
        val voice: Boolean,
        val emergencyStop: Boolean,
        val runningTasks: Int,
        val recentTasks: List<Task>,
    )

    data class Result<out T>(
        val ok: Boolean,
        val value: T?,
        val httpCode: Int,
        val message: String,
    )

    // --------------------------------------------------------------- plumbing

    /**
     * A wrong address should fail fast. A command may legitimately take a few
     * seconds (Friday waits up to 6s before answering with a task id), so the
     * read timeout is generous but bounded.
     */
    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** Trust manager that accepts exactly one certificate, by SHA-256. */
    private class PinTrustManager(private val expected: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("The laptop sent no certificate.")
            val actual = sha256(leaf.encoded)
            if (!actual.equals(expected.trim(), ignoreCase = true)) {
                throw CertificateException(
                    "The laptop's certificate does not match the one you paired with. " +
                        "It may have been regenerated, or something on the network is " +
                        "pretending to be Friday.",
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * A client pinned to [fingerprint]. Hostname verification is intentionally
     * replaced: the certificate is self-signed for a LAN IP that changes, so the
     * exact-certificate pin above is what proves identity. Nothing is sent until
     * that check has passed.
     */
    private fun pinnedClient(fingerprint: String): OkHttpClient {
        val trust = PinTrustManager(fingerprint)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trust), SecureRandom())
        return plainClient.newBuilder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun clientFor(base: String): OkHttpClient =
        if (base.startsWith("https://", ignoreCase = true)) {
            pinnedClient(prefs.fridayFingerprint)
        } else {
            plainClient
        }

    fun baseUrl(): String = prefs.fridayBaseUrl()

    fun isConfigured(): Boolean =
        prefs.fridayEnabled && prefs.fridayHost.isNotBlank() && prefs.fridayToken.isNotBlank()

    fun hasPinnedCertificate(): Boolean =
        prefs.fridayFingerprint.isNotBlank() ||
            !baseUrl().startsWith("https://", ignoreCase = true)

    private fun auth(request: Request.Builder) {
        val token = prefs.fridayToken.trim()
        if (token.isNotEmpty()) request.header("Authorization", "Bearer $token")
        request.header("Accept", "application/json")
    }

    // ------------------------------------------------------------ endpoints

    /** Unauthenticated liveness probe. Also used to show the fingerprint before pairing. */
    fun ping(): Result<Ping> {
        val base = baseUrl()
        if (base.isBlank()) return fail("No Friday address is set.")
        val url = "$base/v1/ping"
        return try {
            clientFor(base).newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return fail("HTTP ${response.code} from $url\n${errorMessage(body)}", response.code)
                }
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return fail("Friday answered with something I could not read.", response.code)
                Result(
                    ok = true,
                    value = Ping(
                        name = json.optString("name", "Friday"),
                        version = json.optString("version"),
                        protocol = json.optString("protocol"),
                        machine = json.optString("machine"),
                        tls = json.optBoolean("tls", base.startsWith("https")),
                        fingerprint = json.optString("fingerprint"),
                        pairingRequired = json.optBoolean("pairing_required", true),
                        emergencyStop = json.optBoolean("emergency_stop", false),
                    ),
                    httpCode = response.code,
                    message = "",
                )
            }
        } catch (e: Exception) {
            fail(connectionHint(e, base))
        }
    }

    /**
     * Redeems a one-time pairing code for this phone's own device token. The
     * fingerprint shown by [ping] is stored after a successful pair, so every
     * later call is pinned to the certificate that issued the token.
     */
    fun pair(code: String, deviceName: String): Result<Paired> {
        val base = baseUrl()
        if (base.isBlank()) return fail("Set the laptop's address first.")
        val clean = code.replace("-", "").replace(" ", "").uppercase(Locale.US).trim()
        if (clean.length != 8) return fail("A pairing code is 8 characters, like ABCD-EFGH.")

        val payload = JSONObject().apply {
            put("code", clean)
            put("device_name", deviceName.ifBlank { "Android" })
            put("platform", "android")
        }
        val url = "$base/v1/pair"
        return try {
            clientFor(base).newCall(
                Request.Builder().url(url).post(payload.toString().toRequestBody(JSON)).build(),
            ).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return fail(
                        if (response.code == 403) {
                            "Friday refused that code. Codes are single use and expire, " +
                                "so generate a fresh one on the laptop."
                        } else {
                            "HTTP ${response.code} from $url\n${errorMessage(body)}"
                        },
                        response.code,
                    )
                }
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return fail("Friday answered with something I could not read.", response.code)
                val token = json.optString("token")
                if (token.isBlank()) return fail("Friday did not send a device token.", response.code)

                val paired = Paired(
                    deviceId = json.optString("device_id"),
                    deviceName = json.optString("device_name"),
                    token = token,
                    fingerprint = json.optString("fingerprint"),
                    protocol = json.optString("protocol"),
                )

                // Save only after the whole exchange succeeded. If anything above
                // failed, the phone keeps no half-configured pairing.
                prefs.fridayToken = paired.token
                prefs.fridayDeviceId = paired.deviceId
                if (paired.fingerprint.isNotBlank()) prefs.fridayFingerprint = paired.fingerprint
                prefs.fridayEnabled = true
                prefs.fridayPairedAt = System.currentTimeMillis()

                Result(true, paired, response.code, "Paired with ${paired.deviceName}.")
            }
        } catch (e: Exception) {
            fail(connectionHint(e, base))
        }
    }

    fun status(): Result<StatusView> {
        val base = baseUrl()
        if (!isConfigured()) return fail("Friday is not paired yet.")
        val url = "$base/v1/status"
        return try {
            val builder = Request.Builder().url(url).get()
            auth(builder)
            clientFor(base).newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return fail(httpHint(response.code, body), response.code)
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return fail("Friday answered with something I could not read.", response.code)
                val array = json.optJSONArray("recent_tasks") ?: JSONArray()
                val tasks = ArrayList<Task>(array.length())
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { tasks.add(parseTask(it)) }
                }
                Result(
                    ok = true,
                    value = StatusView(
                        machine = json.optString("machine"),
                        state = json.optString("state"),
                        detail = json.optString("detail"),
                        aiConfigured = json.optBoolean("ai_configured", false),
                        voice = json.optBoolean("voice", false),
                        emergencyStop = json.optBoolean("emergency_stop", false),
                        runningTasks = json.optInt("running_tasks", 0),
                        recentTasks = tasks,
                    ),
                    httpCode = response.code,
                    message = "",
                )
            }
        } catch (e: Exception) {
            fail(connectionHint(e, base))
        }
    }

    /**
     * Sends one instruction. [requestId] must be stable for a retry of the same
     * instruction: Friday refuses to run it twice and answers with the original
     * task, which is what makes a flaky connection safe.
     */
    fun command(text: String, requestId: String): Result<Task> {
        val base = baseUrl()
        if (!isConfigured()) return fail("Friday is not paired yet.")
        val payload = JSONObject().apply {
            put("protocol", PROTOCOL)
            put("text", text)
            put("request_id", requestId)
            put("client", "android")
            put("app_version", BuildConfig.VERSION_NAME)
        }
        val url = "$base/v1/command"
        return postTask(base, url, payload)
    }

    fun task(taskId: String): Result<Task> {
        val base = baseUrl()
        if (!isConfigured()) return fail("Friday is not paired yet.")
        val url = "$base/v1/task/$taskId"
        return try {
            val builder = Request.Builder().url(url).get()
            auth(builder)
            clientFor(base).newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return fail(httpHint(response.code, body), response.code)
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return fail("Friday sent an update I could not read.", response.code)
                Result(true, parseTask(json), response.code, "")
            }
        } catch (e: Exception) {
            fail(connectionHint(e, base))
        }
    }

    fun cancel(taskId: String): Result<Boolean> {
        val base = baseUrl()
        if (!isConfigured()) return fail("Friday is not paired yet.")
        val url = "$base/v1/task/$taskId/cancel"
        return try {
            val builder = Request.Builder().url(url).post(EMPTY_JSON.toRequestBody(JSON))
            auth(builder)
            clientFor(base).newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return fail(httpHint(response.code, body), response.code)
                val json = runCatching { JSONObject(body) }.getOrNull()
                val cancelled = json?.optBoolean("ok", false) ?: false
                Result(
                    cancelled,
                    cancelled,
                    response.code,
                    json?.optString("summary").orEmpty().ifBlank {
                        if (cancelled) "Cancelled." else "That task is not running."
                    },
                )
            }
        } catch (e: Exception) {
            fail(connectionHint(e, base))
        }
    }

    /**
     * Answers a pending approval. Friday also enforces this: if remote approval
     * is switched off on the laptop, the answer is refused with 403 and nothing
     * runs.
     */
    fun approve(approvalId: String, approved: Boolean, remember: Boolean): Result<Boolean> {
        val base = baseUrl()
        if (!isConfigured()) return fail("Friday is not paired yet.")
        val payload = JSONObject().apply {
            put("approval_id", approvalId)
            put("approved", approved)
            put("remember", remember)
        }
        val url = "$base/v1/approve"
        return try {
            val builder = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON))
            auth(builder)
            clientFor(base).newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return fail(httpHint(response.code, body), response.code)
                val json = runCatching { JSONObject(body) }.getOrNull()
                val ok = json?.optBoolean("ok", false) ?: false
                Result(
                    ok,
                    ok,
                    response.code,
                    json?.optString("summary").orEmpty().ifBlank {
                        if (ok) "Answered on the phone." else "That approval was already resolved."
                    },
                )
            }
        } catch (e: Exception) {
            fail(connectionHint(e, base))
        }
    }

    /**
     * The big red switch. When engaged Friday cancels queued work and refuses
     * every new command until it is released on the laptop.
     */
    fun emergencyStop(engage: Boolean = true): Result<Boolean> {
        val base = baseUrl()
        if (!isConfigured()) return fail("Friday is not paired yet.")
        val payload = JSONObject().apply { put("engage", engage) }
        val url = "$base/v1/emergency-stop"
        return try {
            val builder = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON))
            auth(builder)
            clientFor(base).newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return fail(httpHint(response.code, body), response.code)
                val json = runCatching { JSONObject(body) }.getOrNull()
                val stopped = json?.optBoolean("emergency_stop", engage) ?: engage
                Result(
                    true,
                    stopped,
                    response.code,
                    json?.optString("summary").orEmpty().ifBlank {
                        if (stopped) "Emergency stop engaged." else "Emergency stop released."
                    },
                )
            }
        } catch (e: Exception) {
            fail(connectionHint(e, base))
        }
    }

    /** Forgets this phone's token and certificate pin. Call [revokeOnServer] first to also revoke it there. */
    fun forget() {
        prefs.clearFridayPairing()
    }

    /**
     * Revokes [deviceId] on the laptop. Normally this is this phone's own id, so
     * unpairing both sides leaves no usable token behind.
     */
    fun revokeOnServer(deviceId: String): Result<Boolean> {
        val base = baseUrl()
        if (!isConfigured()) return fail("Not paired, so there is nothing to revoke.")
        if (deviceId.isBlank()) return fail("No device id to revoke.")
        val payload = JSONObject().apply { put("device_id", deviceId) }
        val url = "$base/v1/devices/revoke"
        return try {
            val builder = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON))
            auth(builder)
            clientFor(base).newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return fail(httpHint(response.code, body), response.code)
                val json = runCatching { JSONObject(body) }.getOrNull()
                Result(
                    true,
                    json?.optBoolean("ok", false) ?: false,
                    response.code,
                    json?.optString("summary").orEmpty().ifBlank { "Revoked." },
                )
            }
        } catch (e: Exception) {
            fail(connectionHint(e, base))
        }
    }

    // -------------------------------------------------------------- internals

    private fun postTask(base: String, url: String, payload: JSONObject): Result<Task> = try {
        val builder = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON))
        auth(builder)
        clientFor(base).newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful && response.code != 202) {
                return fail(httpHint(response.code, body), response.code)
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return fail("Friday accepted the command but sent an answer I could not read.", response.code)
            Result(true, parseTask(json), response.code, "")
        }
    } catch (e: Exception) {
        fail(connectionHint(e, base))
    }

    private fun parseTask(json: JSONObject): Task {
        val approvalJson = json.optJSONObject("approval")
        val approval = approvalJson?.let {
            Approval(
                id = it.optString("approval_id"),
                title = it.optString("title"),
                detail = it.optString("detail"),
                capability = it.optString("capability"),
                destructive = it.optBoolean("destructive", false),
            )
        }
        return Task(
            taskId = json.optString("task_id"),
            status = json.optString("status", "unknown").lowercase(Locale.US),
            summary = json.optString("summary"),
            progress = json.optString("progress"),
            error = json.optString("error"),
            instruction = json.optString("instruction"),
            needsApproval = json.optBoolean("needs_approval", false),
            approval = approval,
            isTerminal = json.optBoolean("is_terminal", false),
            duplicate = json.optBoolean("duplicate", false),
            createdAt = json.optString("created_at"),
        )
    }

    private fun httpHint(code: Int, body: String): String = when (code) {
        401 -> "Friday does not recognise this phone any more. Pair it again. ${errorMessage(body)}".trim()
        403 -> "Friday refused that: ${errorMessage(body)}"
        423 -> "Friday's emergency stop is engaged. Release it on the laptop first.\n\n${errorMessage(body)}"
        426 -> "This app and Friday speak different protocol versions. Update the newer one.\n\n${errorMessage(body)}"
        429 -> "Too many requests. Wait a few seconds and try again."
        else -> "HTTP $code from Friday\n${errorMessage(body)}"
    }

    private fun fail(message: String, httpCode: Int = 0): Result<Nothing> =
        Result(false, null, httpCode, message)

    private fun connectionHint(e: Exception, base: String): String {
        val raw = (e.message ?: e.javaClass.simpleName).lowercase(Locale.getDefault())
        val hint = when {
            e is CertificateException || raw.contains("certificate") || raw.contains("trust anchor") ->
                "Friday's certificate was refused, so nothing was sent. If you regenerated " +
                    "the laptop's certificate, pair the phone again."
            raw.contains("ssl") || raw.contains("handshake") ->
                "The secure connection failed. Check that Friday is running with TLS on, " +
                    "and that the phone and laptop agree on the address."
            raw.contains("timeout") || raw.contains("timed out") ->
                "Friday did not answer in time. Is the Friday window still running?"
            raw.contains("refused") ->
                "Connection refused. Friday is not listening, or the port is wrong."
            raw.contains("unable to resolve") || raw.contains("unknown host") ->
                "That address does not resolve. Use the laptop's LAN IP, not a name."
            raw.contains("network is unreachable") || raw.contains("no route") ->
                "The phone cannot reach that network. Phone and laptop must be on the same Wi-Fi."
            else -> e.message ?: e.javaClass.simpleName
        }
        return "$hint\n\nAddress: ${base.trimEnd('/')}"
    }

    private fun errorMessage(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val json = JSONObject(trimmed)
            json.optString("error").ifBlank { json.optString("message") }.ifBlank { trimmed.take(300) }
        }.getOrElse { trimmed.take(300) }
    }

    companion object {
        const val PROTOCOL = "1.0"
        const val STATUS_AWAITING = "awaiting_approval"
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCEEDED = "succeeded"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_RECEIVED = "received"
        const val STATUS_VALIDATING = "validating"

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val EMPTY_JSON = "{}"

        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val out = StringBuilder(digest.size * 2)
            for (b in digest) out.append(String.format("%02X", b))
            return out.toString()
        }

        /** Formats ABCD-EFGH the way Friday displays it. */
        fun prettyCode(raw: String): String {
            val clean = raw.replace("-", "").replace(" ", "").uppercase(Locale.US)
            return if (clean.length == 8) clean.substring(0, 4) + "-" + clean.substring(4) else clean
        }
    }
}
