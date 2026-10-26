package com.bonjee.notifier.whatsapp

import android.content.Context
import android.content.SharedPreferences
import com.bonjee.notifier.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local message + draft index.
 *
 * It is the app's own copy, not the system of record: the backend keeps the
 * authoritative conversation. Keeping it locally means the inbox still opens,
 * and an approved reply still shows its last known state, after the process is
 * killed or the phone is offline.
 *
 * Bounded to [MAX_RECORDS] so a busy group cannot grow the file without limit.
 */
class MessageStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = Prefs.get(app)
    private val sp: SharedPreferences = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): MessageBox {
        val raw = sp.getString(KEY_MESSAGES, "") ?: ""
        if (raw.isBlank()) return MessageBox()
        return try {
            val array = JSONArray(raw)
            val items = ArrayList<MessageRecord>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                items.add(fromJson(o))
            }
            MessageBox(items)
        } catch (t: Throwable) {
            MessageBox()
        }
    }

    @Synchronized
    fun save(box: MessageBox) {
        val trimmed = box.newestFirst().take(MAX_RECORDS)
        val array = JSONArray()
        trimmed.forEach { array.put(toJson(it)) }
        sp.edit().putString(KEY_MESSAGES, array.toString()).apply()
    }

    fun upsert(record: MessageRecord): MessageBox {
        val box = load().upsert(record)
        save(box)
        return box
    }

    fun find(messageId: String?): MessageRecord? = load().find(messageId)

    /** Finds a message by id, or the newest one for a sender when id is blank. */
    fun findOrLatest(messageId: String?, sender: String?): MessageRecord? {
        val byId = find(messageId)
        if (byId != null) return byId
        if (sender.isNullOrBlank()) return null
        return load().forSender(sender, prefs.defaultCountryCode).firstOrNull()
    }

    fun remove(messageId: String?): MessageBox {
        val box = load().remove(messageId)
        save(box)
        return box
    }

    fun recent(limit: Int = 100): List<MessageRecord> = load().recent(limit)

    fun pendingApproval(): List<MessageRecord> = load().pendingApproval()

    fun needsStatusCheck(): List<MessageRecord> = load().needsStatusCheck()

    /** Applies a backend status; returns null when the transition is illegal. */
    fun applyRemoteStatus(messageId: String, status: String?): MessageBox? {
        val next = load().applyRemoteStatus(messageId, status) ?: return null
        save(next)
        return next
    }

    fun clear() {
        sp.edit().remove(KEY_MESSAGES).apply()
    }

    fun count(): Int = load().items.size

    @Synchronized
    fun pruneOlderThan(cutoff: Long) {
        val box = load()
        val kept = MessageBox(box.items.filter { it.receivedAt >= cutoff || it.state != MessageState.SENT })
        if (kept.items.size != box.items.size) save(kept)
    }

    private fun toJson(r: MessageRecord): JSONObject = JSONObject().apply {
        put("message_id", r.messageId)
        put("conversation_id", r.conversationId)
        put("sender", r.sender)
        put("sender_name", r.senderName)
        put("category", r.category.wire)
        put("text", r.text)
        put("message_type", r.messageType)
        put("received_at", r.receivedAt)
        put("state", r.state.wire)
        put("draft", r.draft)
        put("instructions", r.instructions)
        put("request_id", r.requestId)
        put("error", r.error)
        put("audit", r.audit)
        put("updated_at", r.updatedAt)
        put("inbound", r.inbound)
    }

    private fun fromJson(o: JSONObject): MessageRecord = MessageRecord(
        messageId = o.optString("message_id"),
        conversationId = o.optString("conversation_id"),
        sender = o.optString("sender"),
        senderName = o.optString("sender_name"),
        category = ContactCategory.fromWire(o.optString("category")),
        text = o.optString("text"),
        messageType = o.optString("message_type", "text"),
        receivedAt = o.optLong("received_at", 0L),
        state = MessageState.fromWire(o.optString("state")),
        draft = o.optString("draft"),
        instructions = o.optString("instructions"),
        requestId = o.optString("request_id"),
        error = o.optString("error"),
        audit = o.optString("audit"),
        updatedAt = o.optLong("updated_at", 0L),
        inbound = o.optBoolean("inbound", true),
    )

    companion object {
        private const val FILE = "bonjee_whatsapp"
        private const val KEY_MESSAGES = "messages_v1"
        private const val MAX_RECORDS = 300
    }
}
