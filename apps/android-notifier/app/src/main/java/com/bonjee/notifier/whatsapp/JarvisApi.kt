package com.bonjee.notifier.whatsapp

import com.bonjee.notifier.ApiClient
import com.bonjee.notifier.BuildConfig
import com.bonjee.notifier.Prefs
import org.json.JSONArray
import org.json.JSONObject
import android.content.Context

/**
 * The only door between the app and n8n.
 *
 * Every call posts the same versioned envelope to the one app-facing path
 * ([Prefs.appApiUrl]). There is deliberately no method that calls a different
 * URL: adding an action means adding a payload builder here, not a webhook.
 * The Evolution API inbound webhook is server-to-server and is never touched.
 *
 * Security notes:
 *  - no key is embedded; the optional auth header comes from user settings;
 *  - the device id is a correlation key, not authentication - the workflow must
 *    verify the token server-side and must not trust `device_id`/`sender`;
 *  - a 2xx response with an empty body is reported as "accepted, status
 *    unknown", never as "sent".
 */
class JarvisApi(context: Context) {

    private val prefs = Prefs.get(context.applicationContext)
    private val client = ApiClient.shared

    /** One response, with the raw body kept so the UI can show real details. */
    data class EnvelopeResult(
        val action: String,
        val requestId: String,
        val ok: Boolean,
        val code: Int,
        val body: String,
        val error: String?,
    ) {
        val json: JSONObject? by lazy {
            runCatching { if (body.isBlank()) null else JSONObject(body) }.getOrNull()
        }

        val endpointMissing: Boolean get() = code == 404
        val unauthorized: Boolean get() = code == 401 || code == 403
        val offline: Boolean get() = code == 0

        /** The `data` object some responses wrap their fields in. */
        private val data: JSONObject? get() = json?.optJSONObject("data")

        fun field(name: String): String {
            val fromData = data?.optString(name).orEmpty()
            if (fromData.isNotBlank()) return fromData
            return json?.optString(name).orEmpty()
        }

        fun boolField(name: String): Boolean? {
            val d = data
            if (d != null && d.has(name)) return d.optBoolean(name)
            val j = json ?: return null
            return if (j.has(name)) j.optBoolean(name) else null
        }

        /** Reply text from `reply.generate`, wherever the workflow put it. */
        fun proposedReply(): String {
            val candidates = listOf("reply", "proposed_reply", "text", "draft", "message")
            for (key in candidates) {
                val value = field(key)
                if (value.isNotBlank()) return value
            }
            return ""
        }

        /** Real send state from the backend; null means "not reported". */
        fun sendStatus(): String? =
            field("status").ifBlank { field("send_status") }.takeIf { it.isNotBlank() }

        fun requests(): List<String> {
            val array: JSONArray = data?.optJSONArray("requests") ?: json?.optJSONArray("requests") ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("message_id") }
        }

        /** The sentence shown to the user. Never invents a success. */
        fun userMessage(): String = when {
            error != null -> "Can't reach the workflow ($error). Nothing was sent."
            endpointMissing ->
                "No app endpoint at the configured URL (HTTP 404). Your workflow has not been " +
                    "updated with the app entry point yet, so nothing was sent."
            unauthorized ->
                "The workflow rejected this device (HTTP $code). Add the correct token under Devices & n8n."
            code == 400 || code == 422 ->
                "The workflow rejected the request (HTTP $code): ${body.take(240)}"
            code >= 500 ->
                "The workflow failed (HTTP $code). Nothing was sent."
            !ok ->
                "The workflow refused this action (HTTP $code): ${body.take(240)}"
            else -> "Accepted by the workflow (HTTP $code)."
        }
    }

    fun isConfigured(): Boolean =
        prefs.n8nBaseUrl.isNotBlank() && prefs.appApiPath.isNotBlank()

    /**
     * Posts one versioned envelope.
     *
     * @param safeToRetry only true for read-only/idempotent actions. A send is
     *   never retried here: a timeout must be resolved with `reply.status`.
     */
    fun call(
        action: String,
        payload: JSONObject,
        requestId: String = RequestIds.new(),
        safeToRetry: Boolean = false,
    ): EnvelopeResult {
        val envelope = JSONObject().apply {
            put("version", 1)
            put("request_id", requestId)
            put("device_id", prefs.deviceId)
            put("action", action)
            put("timestamp", Iso8601.now())
            put("payload", payload)
        }
        val url = prefs.appApiUrl()
        var result = post(url, envelope.toString())
        if (safeToRetry && result.code == 0) {
            // One retry for operations that cannot send anything twice.
            result = post(url, envelope.toString())
        }
        return EnvelopeResult(
            action = action,
            requestId = requestId,
            ok = result.ok,
            code = result.code,
            body = result.body,
            error = result.error,
        )
    }

    private fun post(url: String, body: String): ApiClient.ApiResult =
        client.postJson(url, body, prefs.headers())

    // -------------------------------------------------------------- actions

    fun acknowledge(record: MessageRecord, rule: ContactRule?): EnvelopeResult = call(
        ACTION_ACKNOWLEDGE,
        JSONObject().apply {
            put("message_id", record.messageId)
            put("conversation_id", record.conversationId)
            put("sender", record.sender)
            put("sender_name", record.senderName)
            put("received_at", record.receivedAt)
            put("message_type", record.messageType)
            put("category", record.category.wire)
            put("auto_reply", rule?.autoReplyEnabled == true)
        },
        safeToRetry = true,
    )

    fun generate(
        record: MessageRecord,
        instructions: String = "",
        revision: Int = 0,
        automatic: Boolean = false,
        rule: ContactRule? = null,
    ): EnvelopeResult = call(
        ACTION_GENERATE,
        JSONObject().apply {
            put("conversation_id", record.conversationId)
            put("message_id", record.messageId)
            put("sender", record.sender)
            put("sender_name", record.senderName)
            put("text", record.text)
            put("instructions", instructions)
            put("mode", if (automatic) "auto" else "approval")
            put("revision", revision)
            put("contact_rule", ruleJson(rule))
        },
        requestId = RequestIds.generate(record.messageId, revision),
    )

    fun approve(
        record: MessageRecord,
        finalText: String,
        attempt: Int = 0,
        automatic: Boolean = false,
        rule: ContactRule? = null,
    ): EnvelopeResult = call(
        ACTION_APPROVE,
        JSONObject().apply {
            put("conversation_id", record.conversationId)
            put("message_id", record.messageId)
            put("sender", record.sender)
            put("text", finalText)
            put("approved", true)
            put("auto", automatic)
            put("contact_rule", ruleJson(rule))
        },
        requestId = RequestIds.send(record.messageId, attempt),
    )

    fun cancel(record: MessageRecord, reason: String = "user cancelled"): EnvelopeResult = call(
        ACTION_CANCEL,
        JSONObject().apply {
            put("conversation_id", record.conversationId)
            put("message_id", record.messageId)
            put("reason", reason)
        },
        safeToRetry = true,
    )

    fun status(record: MessageRecord): EnvelopeResult = call(
        ACTION_STATUS,
        JSONObject().apply {
            put("message_id", record.messageId)
            put("conversation_id", record.conversationId)
            put("request_id", record.requestId)
        },
        safeToRetry = true,
    )

    fun history(sender: String, limit: Int = 20): EnvelopeResult = call(
        ACTION_HISTORY,
        JSONObject().apply {
            put("sender", sender)
            put("limit", limit)
        },
        safeToRetry = true,
    )

    fun updateContactSettings(rule: ContactRule, serverAuthorized: Boolean): EnvelopeResult = call(
        ACTION_CONTACT_SETTINGS,
        JSONObject().apply {
            put("number", rule.number)
            put("name", rule.name)
            put("category", rule.category.wire)
            put("auto_reply_enabled", rule.autoReplyEnabled)
            put("auto_reply_mode", rule.autoReplyMode.wire)
            put("template", rule.template)
            put("announce", rule.announce)
            put("priority", rule.priority)
            put("requested_authorization", serverAuthorized)
        },
        safeToRetry = true,
    )

    fun memorySearch(query: String, limit: Int = 10): EnvelopeResult = call(
        ACTION_MEMORY_SEARCH,
        JSONObject().apply {
            put("query", query)
            put("limit", limit)
        },
        safeToRetry = true,
    )

    fun createTask(title: String, due: String = "", messageId: String = ""): EnvelopeResult = call(
        ACTION_TASK_CREATE,
        JSONObject().apply {
            put("title", title)
            put("due", due)
            put("source_message_id", messageId)
        },
        safeToRetry = true,
    )

    /**
     * Registers this install with the workflow. No push token is invented: the
     * project has no FCM dependency, so delivery uses the notification listener
     * and the contract documents FCM as an optional backend addition.
     */
    fun registerDevice(pushToken: String = "", pushProvider: String = "none"): EnvelopeResult = call(
        ACTION_DEVICE_REGISTER,
        JSONObject().apply {
            put("device_name", prefs.deviceName)
            put("platform", "android")
            put("app_version", BuildConfig.VERSION_NAME)
            put("push_provider", pushProvider)
            put("push_token", pushToken)
            put("notification_capture", prefs.whatsappCaptureEnabled)
        },
        safeToRetry = true,
    )

    private fun ruleJson(rule: ContactRule?): JSONObject = JSONObject().apply {
        if (rule == null) {
            put("category", ContactCategory.OTHER.wire)
            put("auto_reply_enabled", false)
            put("auto_reply_mode", AutoReplyMode.OFF.wire)
            return@apply
        }
        put("number", rule.number)
        put("category", rule.category.wire)
        put("auto_reply_enabled", rule.autoReplyEnabled)
        put("auto_reply_mode", rule.autoReplyMode.wire)
        put("template", rule.template)
    }

    /** Parses `conversation.history` into records we can show. */
    fun parseHistory(result: EnvelopeResult, fallbackSender: String): List<MessageRecord> {
        val array = result.json?.optJSONArray("messages")
            ?: result.json?.optJSONObject("data")?.optJSONArray("messages")
            ?: return emptyList()
        val out = ArrayList<MessageRecord>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("message_id").ifBlank { o.optString("id") }
            if (id.isBlank()) continue
            out.add(
                MessageRecord(
                    messageId = id,
                    conversationId = o.optString("conversation_id"),
                    sender = PhoneNumber.normalize(o.optString("sender"), prefs.defaultCountryCode)
                        .ifEmpty { fallbackSender },
                    senderName = o.optString("sender_name"),
                    category = ContactCategory.fromWire(o.optString("category")),
                    text = o.optString("text"),
                    messageType = o.optString("message_type", "text"),
                    receivedAt = o.optLong("timestamp", o.optLong("received_at", 0L)),
                    state = MessageState.fromWire(o.optString("state")),
                    inbound = o.optBoolean("inbound", true),
                ),
            )
        }
        return out
    }

    companion object {
        const val ACTION_ACKNOWLEDGE = "message.acknowledge"
        const val ACTION_GENERATE = "reply.generate"
        const val ACTION_APPROVE = "reply.approve"
        const val ACTION_CANCEL = "reply.cancel"
        const val ACTION_STATUS = "reply.status"
        const val ACTION_HISTORY = "conversation.history"
        const val ACTION_CONTACT_SETTINGS = "contact.settings.update"
        const val ACTION_MEMORY_SEARCH = "memory.search"
        const val ACTION_TASK_CREATE = "task.create"
        const val ACTION_DEVICE_REGISTER = "device.register"

        val ALL_ACTIONS = listOf(
            ACTION_ACKNOWLEDGE, ACTION_GENERATE, ACTION_APPROVE, ACTION_CANCEL, ACTION_STATUS,
            ACTION_HISTORY, ACTION_CONTACT_SETTINGS, ACTION_MEMORY_SEARCH, ACTION_TASK_CREATE,
            ACTION_DEVICE_REGISTER,
        )
    }
}
