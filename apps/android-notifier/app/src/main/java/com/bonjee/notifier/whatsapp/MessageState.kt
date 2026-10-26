package com.bonjee.notifier.whatsapp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * The explicit lifecycle of an inbound message and its reply.
 *
 * Nothing may jump straight from [RECEIVED] to [SENT]: an AI reply has to pass
 * through an approval state, and only a backend send result can mark [SENT].
 */
enum class MessageState(val wire: String, val label: String) {
    RECEIVED("received", "Received"),
    PENDING_APPROVAL("pending_approval", "Waiting for your approval"),
    APPROVED("approved", "Approved"),
    SENDING("sending", "Sending"),
    SENT("sent", "Sent"),
    FAILED("failed", "Failed"),
    CANCELLED("cancelled", "Cancelled");

    companion object {
        fun fromWire(value: String?): MessageState =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: RECEIVED
    }
}

/**
 * Transition rules for a reply. Pure so the "never send without approval" and
 * "never double-send after a timeout" guarantees can be tested without a device.
 */
object ReplyFlow {

    private val allowed: Map<MessageState, Set<MessageState>> = mapOf(
        MessageState.RECEIVED to setOf(
            MessageState.PENDING_APPROVAL,
            MessageState.CANCELLED,
        ),
        MessageState.PENDING_APPROVAL to setOf(
            MessageState.APPROVED,
            MessageState.CANCELLED,
            // A regenerated draft replaces the previous one; it stays pending.
            MessageState.PENDING_APPROVAL,
        ),
        MessageState.APPROVED to setOf(
            MessageState.SENDING,
            MessageState.CANCELLED,
            // A new draft can supersede an approved one before it is sent.
            MessageState.PENDING_APPROVAL,
        ),
        // SENDING may only resolve to a confirmed result. Re-entering SENDING is
        // allowed because a timeout is resolved by a status query, not a resend.
        MessageState.SENDING to setOf(
            MessageState.SENT,
            MessageState.FAILED,
            MessageState.SENDING,
        ),
        MessageState.SENT to emptySet(),
        MessageState.FAILED to setOf(
            // Retry goes back through approval: the text must be seen again.
            MessageState.PENDING_APPROVAL,
            MessageState.CANCELLED,
            // A status query may reveal the send actually succeeded before the
            // timeout; that resolves to SENT without ever resending.
            MessageState.SENT,
        ),
        MessageState.CANCELLED to emptySet(),
    )

    fun canTransition(from: MessageState, to: MessageState): Boolean =
        allowed[from]?.contains(to) == true

    fun isTerminal(state: MessageState): Boolean =
        state == MessageState.SENT || state == MessageState.CANCELLED

    /** Only the backend can confirm these; the app must never assume them. */
    fun requiresStatusQuery(state: MessageState): Boolean =
        state == MessageState.SENDING || state == MessageState.APPROVED

    fun canRetry(state: MessageState): Boolean = state == MessageState.FAILED

    /** Reads a reply status response and decides the next local state. */
    fun resolveRemoteStatus(status: String?): MessageState? = when (status?.trim()?.lowercase()) {
        "sent", "delivered", "read" -> MessageState.SENT
        "failed", "error", "rejected" -> MessageState.FAILED
        "sending", "queued", "pending" -> MessageState.SENDING
        "cancelled", "canceled" -> MessageState.CANCELLED
        "pending_approval", "draft" -> MessageState.PENDING_APPROVAL
        "approved" -> MessageState.APPROVED
        else -> null
    }
}

/** Small helpers shared by the API and UI layers. */
object Iso8601 {
    private val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Synchronized
    fun now(at: Long = System.currentTimeMillis()): String = format.format(Date(at))
}

object RequestIds {
    /** A fresh idempotency key. Never reused across retries. */
    fun new(): String = UUID.randomUUID().toString()

    /**
     * Stable key for one logical send. Two app instances that both replay the
     * same approval produce the same key, so the backend can de-duplicate.
     */
    fun send(messageId: String, attempt: Int = 0): String {
        val base = messageId.trim().ifEmpty { "unknown" }
        val suffix = if (attempt > 0) "-r$attempt" else ""
        return "send-$base$suffix"
    }

    fun generate(messageId: String, revision: Int = 0): String {
        val base = messageId.trim().ifEmpty { "unknown" }
        val suffix = if (revision > 0) "-v$revision" else ""
        return "gen-$base$suffix"
    }
}

/**
 * One inbound WhatsApp message plus everything the user did with it.
 *
 * [requestId] is the idempotency key of the request that most recently moved
 * this record, so a retry can be recognised instead of shipping a second reply.
 */
data class MessageRecord(
    val messageId: String,
    val conversationId: String = "",
    val sender: String,
    val senderName: String = "",
    val category: ContactCategory = ContactCategory.OTHER,
    val text: String = "",
    val messageType: String = "text",
    val receivedAt: Long = 0L,
    val state: MessageState = MessageState.RECEIVED,
    val draft: String = "",
    val instructions: String = "",
    val requestId: String = "",
    val error: String = "",
    val audit: String = "",
    val updatedAt: Long = 0L,
    val inbound: Boolean = true,
) {
    val displaySender: String
        get() = senderName.trim().ifEmpty { PhoneNumber.pretty(sender) }

    val isImportant: Boolean get() = category == ContactCategory.IMPORTANT

    /** True only when the exact text has been approved and confirmed by the backend. */
    val isConfirmedSent: Boolean get() = state == MessageState.SENT

    fun transition(
        to: MessageState,
        draft: String = this.draft,
        instructions: String = this.instructions,
        requestId: String = this.requestId,
        error: String = "",
        audit: String = this.audit,
        at: Long = System.currentTimeMillis(),
    ): MessageRecord? {
        if (!ReplyFlow.canTransition(state, to)) return null
        return copy(
            state = to,
            draft = draft,
            instructions = instructions,
            requestId = requestId,
            error = error,
            audit = audit,
            updatedAt = at,
        )
    }
}

/**
 * Local conversation index. Records are keyed by message id so a replayed push
 * (or a duplicate notification listener callback) cannot create a second row.
 */
data class MessageBox(val items: List<MessageRecord> = emptyList()) {

    fun find(messageId: String?): MessageRecord? =
        items.firstOrNull { it.messageId == messageId }

    /** Insert or replace by message id. Newer local state wins. */
    fun upsert(record: MessageRecord): MessageBox {
        val index = items.indexOfFirst { it.messageId == record.messageId }
        if (index < 0) return MessageBox(items + record)
        val old = items[index]
        val merged = record.copy(
            receivedAt = if (record.receivedAt > 0) record.receivedAt else old.receivedAt,
            draft = record.draft.ifBlank { old.draft },
            instructions = record.instructions.ifBlank { old.instructions },
            senderName = record.senderName.ifBlank { old.senderName },
            conversationId = record.conversationId.ifBlank { old.conversationId },
            audit = record.audit.ifBlank { old.audit },
        )
        return MessageBox(items.toMutableList().also { it[index] = merged })
    }

    fun remove(messageId: String?): MessageBox =
        MessageBox(items.filterNot { it.messageId == messageId })

    fun newestFirst(): List<MessageRecord> = items.sortedByDescending { it.receivedAt }

    fun recent(limit: Int = 50): List<MessageRecord> = newestFirst().take(limit.coerceAtLeast(1))

    fun forSender(rawNumber: String?, defaultCountryCode: String = PhoneNumber.DEFAULT_COUNTRY_CODE): List<MessageRecord> {
        val key = PhoneNumber.normalize(rawNumber, defaultCountryCode)
        if (key.isEmpty()) return emptyList()
        return newestFirst().filter { it.sender == key }
    }

    fun latestFor(sender: MessageRecord?): MessageRecord? {
        if (sender == null) return null
        return forSender(sender.sender).firstOrNull()
    }

    fun pendingApproval(): List<MessageRecord> =
        newestFirst().filter { it.state == MessageState.PENDING_APPROVAL }

    fun needsStatusCheck(): List<MessageRecord> =
        items.filter { ReplyFlow.requiresStatusQuery(it.state) }

    fun byState(state: MessageState): List<MessageRecord> = items.filter { it.state == state }

    fun counts(): Map<MessageState, Int> = MessageState.entries.associateWith { s -> items.count { it.state == s } }

    /**
     * Resolves an uncertain send without resending: applies the backend status.
     * Returns null when the status is unknown or the transition is illegal, in
     * which case the record is intentionally left untouched.
     */
    fun applyRemoteStatus(messageId: String, status: String?, at: Long = System.currentTimeMillis()): MessageBox? {
        val record = find(messageId) ?: return null
        val next = ReplyFlow.resolveRemoteStatus(status) ?: return null
        if (next == record.state) return this
        if (!ReplyFlow.canTransition(record.state, next)) return null
        return upsert(record.copy(state = next, updatedAt = at))
    }
}
