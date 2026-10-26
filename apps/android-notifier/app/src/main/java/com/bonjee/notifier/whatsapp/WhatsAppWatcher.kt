package com.bonjee.notifier.whatsapp

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import com.bonjee.notifier.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Turns WhatsApp notifications into the app's message pipeline.
 *
 * This is the delivery path that actually works in this project: there is no
 * FCM dependency, so a closed app is woken by Android's notification listener
 * (which the user explicitly granted) rather than by an invented push channel.
 * The workflow may still add FCM later - the contract documents it - but the
 * app does not pretend to have it.
 *
 * Ordering is deliberate: parse, dedupe, persist and show the notification
 * synchronously (fast, local), then talk to n8n on a background thread. A slow
 * or missing backend can never delay the notification or the capture queue.
 */
object WhatsAppWatcher {

    private const val TAG = "JarvisWhatsApp"
    private const val DUPLICATE_WINDOW_MS = 15_000L

    val packages = setOf("com.whatsapp", "com.whatsapp.w4b")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Inbound(
        val sender: String,
        val senderName: String,
        val text: String,
        val type: String,
        val group: Boolean,
        val postTime: Long,
        val conversationId: String,
    )

    fun isWhatsApp(packageName: String?): Boolean = packages.contains(packageName)

    fun handle(context: Context, sbn: StatusBarNotification?, prefs: Prefs): Boolean {
        val inbound = parse(context, sbn) ?: return false
        if (!prefs.whatsappCaptureEnabled) return false

        val store = MessageStore(context)
        val rules = ContactRulesStore(context)

        // Dedupe: WhatsApp re-posts the same notification (and the listener can
        // fire twice) with an identical body. The id alone is not enough.
        val existing = store.load()
        val duplicate = existing.items.any {
            it.conversationId == inbound.conversationId &&
                it.text == inbound.text &&
                kotlin.math.abs(it.receivedAt - inbound.postTime) < DUPLICATE_WINDOW_MS
        }
        if (duplicate) return false

        val rule = rules.ruleFor(inbound.sender.takeIf { it.isNotBlank() }, inbound.senderName)
            ?: rules.findByName(inbound.senderName)

        // A saved rule can supply the number when contacts access is unavailable
        // and WhatsApp only showed a name.
        val effectiveSender = inbound.sender.ifBlank { rule?.number.orEmpty() }

        val category = when {
            inbound.group -> ContactCategory.OTHER
            rule != null -> rule.category
            else -> ContactCategory.OTHER
        }

        val record = MessageRecord(
            messageId = messageId(sbn, inbound),
            conversationId = inbound.conversationId,
            sender = effectiveSender,
            senderName = inbound.senderName,
            category = category,
            text = inbound.text,
            messageType = inbound.type,
            receivedAt = inbound.postTime,
            state = MessageState.RECEIVED,
            updatedAt = System.currentTimeMillis(),
        )
        store.upsert(record)

        val important = category == ContactCategory.IMPORTANT && !inbound.group
        val announcer = Announcer.get(context)
        val audio = if (important) announcer.decide(true) else AudioDecision(false, false, "not an important contact")

        if (important || prefs.notifyOtherSenders) {
            ImportantNotifier.show(
                context = context,
                record = record,
                prefs = prefs,
                heldReason = if (important) AudioPolicy.suppressionHint(audio) else null,
            )
        }
        if (audio.speak) announcer.announce(record, audio)

        val decision = if (inbound.group) {
            AutoReplyDecision.Ignore("group message")
        } else {
            val policyContact = rule ?: ContactRule(
                number = effectiveSender,
                name = inbound.senderName,
                category = ContactCategory.OTHER,
            )
            AutoReplyPolicy.decide(
                AutoReplyInput(
                    contact = policyContact,
                    globalEnabled = prefs.autoReplyGlobalEnabled,
                    serverAuthorized = policyContact.number.isNotBlank() && prefs.isServerAuthorized(policyContact.number),
                    isGroup = inbound.group,
                    text = inbound.text,
                ),
            )
        }

        val audit = AutoReplyPolicy.describe(decision)
        store.upsert(store.find(record.messageId)?.copy(audit = audit) ?: record)

        // Network talk happens off the notification thread.
        scope.launch {
            try {
                syncWithBackend(context, record, rule, decision, audit)
            } catch (t: Throwable) {
                Log.w(TAG, "Backend sync failed: ${t.message}")
            }
        }
        return true
    }

    /** Parses a WhatsApp notification; null means "not a message we can use". */
    fun parse(context: Context, sbn: StatusBarNotification?): Inbound? {
        if (sbn == null || !isWhatsApp(sbn.packageName)) return null
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null

        fun str(key: String): String = extras.getCharSequence(key)?.toString().orEmpty().trim()

        val rawTitle = str(Notification.EXTRA_TITLE)
        if (rawTitle.isBlank() || rawTitle.equals("WhatsApp", ignoreCase = true)) return null
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null

        val title = rawTitle.replace(Regex("""\s*\(\d+\s*messages?\)$"""), "").trim()

        var text = str(Notification.EXTRA_BIG_TEXT)
        if (text.isBlank()) text = str(Notification.EXTRA_TEXT)
        if (text.isBlank()) text = str(Notification.EXTRA_SUMMARY_TEXT)
        if (text.isBlank()) return null

        // WhatsApp marks messages sent from this phone with "You: ".
        if (text.startsWith("You:") || text.startsWith("You :")) return null

        val group = extras.getBoolean("android.isGroupConversation", false)
        val sub = str(Notification.EXTRA_SUB_TEXT)

        if (group) {
            // Group notifications prefix each line with the sender's name.
            val body = text.substringAfter(": ", text).trim()
            return Inbound(
                sender = "",
                senderName = title,
                text = body.ifBlank { text },
                type = typeOf(text),
                group = true,
                postTime = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis(),
                conversationId = "wa-group:" + hash(title),
            )
        }

        val numberCandidate = when {
            PhoneNumber.looksLikeNumber(title) -> title
            PhoneNumber.looksLikeNumber(sub) -> sub
            else -> ""
        }

        var sender = PhoneNumber.normalize(numberCandidate)
        var name = title
        if (sender.isEmpty()) {
            // Saved contact: the notification only carries the display name.
            val saved = ContactResolver.numberFor(context, title)
            if (saved != null) {
                sender = saved.number
                name = saved.name.ifBlank { title }
            }
        } else {
            name = ContactResolver.nameFor(context, sender) ?: title
        }

        // Prefer a locally stored rule when the title is a name but contacts are
        // unavailable - it is the only way a saved rule could still match.
        if (sender.isEmpty()) {
            val byName = ContactRulesStore(context).findByName(title)
            if (byName != null) {
                sender = byName.number
                name = byName.name.ifBlank { title }
            }
        }

        val conversationId = if (sender.isNotBlank()) "wa:$sender" else "wa-name:" + hash(title)

        return Inbound(
            sender = sender,
            senderName = name,
            text = text,
            type = typeOf(text),
            group = false,
            postTime = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis(),
            conversationId = conversationId,
        )
    }

    // --------------------------------------------------------------- backend

    private fun syncWithBackend(
        context: Context,
        record: MessageRecord,
        rule: ContactRule?,
        decision: AutoReplyDecision,
        audit: String,
    ) {
        val api = JarvisApi(context)
        if (!api.isConfigured()) return

        val store = MessageStore(context)

        // 1. Acknowledge. Best effort: Evolution API is the system of record for
        //    inbound delivery, so a failed ack must not hide the message.
        api.acknowledge(record, rule)

        // 2. Auto-reply, if the user configured it AND the workflow authorised it.
        when (decision) {
            is AutoReplyDecision.Ignore -> Unit
            is AutoReplyDecision.SendTemplate -> sendTemplate(api, store, record, rule, decision, audit)
            is AutoReplyDecision.AskBackend -> askBackend(api, store, record, rule, audit)
        }
    }

    /**
     * A pre-approved template. It still goes through `reply.approve` so the
     * workflow performs the actual send and reports the real status - the app
     * never writes to WhatsApp behind the user's back.
     */
    private fun sendTemplate(
        api: JarvisApi,
        store: MessageStore,
        record: MessageRecord,
        rule: ContactRule?,
        decision: AutoReplyDecision.SendTemplate,
        audit: String,
    ) {
        val current = store.find(record.messageId) ?: record
        val pending = current.transition(
            MessageState.PENDING_APPROVAL,
            draft = decision.text,
            audit = "$audit; template queued",
        ) ?: current.copy(draft = decision.text, audit = audit)
        val approved = pending.transition(MessageState.APPROVED, draft = decision.text, audit = "$audit; template approved by rule")
            ?: pending
        val sending = approved.transition(MessageState.SENDING, draft = decision.text, audit = "$audit; sending")
            ?: approved
        store.upsert(sending)

        val result = api.approve(sending, decision.text, automatic = true, rule = rule)
        applySendResult(store, sending, result, audit)
    }

    /** AI auto-reply: the workflow decides and sends; we only record the result. */
    private fun askBackend(
        api: JarvisApi,
        store: MessageStore,
        record: MessageRecord,
        rule: ContactRule?,
        audit: String,
    ) {
        val result = api.generate(record, automatic = true, rule = rule)
        if (!result.ok) {
            val failed = record.copy(
                error = result.userMessage(),
                audit = "$audit; generate failed",
                updatedAt = System.currentTimeMillis(),
            )
            store.upsert(failed)
            return
        }

        val reply = result.proposedReply()
        val remote = result.sendStatus()?.let { ReplyFlow.resolveRemoteStatus(it) }
        val current = store.find(record.messageId) ?: record

        if (remote == MessageState.SENT || remote == MessageState.SENDING) {
            store.upsert(
                current.copy(
                    state = remote,
                    draft = reply,
                    requestId = result.requestId,
                    audit = "$audit; backend reported ${remote.wire}",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            return
        }

        if (reply.isNotBlank()) {
            // The workflow produced a draft but wants explicit approval.
            val pending = current.transition(
                MessageState.PENDING_APPROVAL,
                draft = reply,
                requestId = result.requestId,
                audit = "$audit; draft ready for approval",
            ) ?: current.copy(draft = reply, audit = "$audit; draft ready for approval")
            store.upsert(pending)
            return
        }

        store.upsert(
            current.copy(
                requestId = result.requestId,
                audit = "$audit; backend accepted (no reply text)",
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Maps a real backend send result onto local state. Never invents "sent". */
    private fun applySendResult(
        store: MessageStore,
        record: MessageRecord,
        result: JarvisApi.EnvelopeResult,
        audit: String,
    ) {
        val current = store.find(record.messageId) ?: record
        val remote = result.sendStatus()?.let { ReplyFlow.resolveRemoteStatus(it) }

        if (!result.ok) {
            val failed = current.transition(
                MessageState.FAILED,
                requestId = result.requestId,
                error = result.userMessage(),
                audit = "$audit; send failed",
            ) ?: current.copy(
                state = MessageState.FAILED,
                requestId = result.requestId,
                error = result.userMessage(),
                audit = "$audit; send failed",
                updatedAt = System.currentTimeMillis(),
            )
            store.upsert(failed)
            return
        }

        val target = remote ?: MessageState.SENDING
        val updated = if (ReplyFlow.canTransition(current.state, target)) {
            current.transition(
                target,
                requestId = result.requestId,
                audit = "$audit; backend reported ${target.wire}",
            ) ?: current
        } else {
            current.copy(
                state = target,
                requestId = result.requestId,
                audit = "$audit; backend reported ${target.wire}",
                updatedAt = System.currentTimeMillis(),
            )
        }
        store.upsert(updated)
    }

    // ---------------------------------------------------------------- helpers

    private fun typeOf(text: String): String = when {
        text.startsWith("📷") || text.startsWith("Photo") -> "image"
        text.startsWith("🎥") || text.startsWith("📹") || text.startsWith("Video") -> "video"
        text.startsWith("🎤") || text.startsWith("Voice message") -> "voice"
        text.startsWith("📄") || text.startsWith("📎") || text.startsWith("Document") -> "document"
        text.startsWith("📍") || text.startsWith("Location") -> "location"
        text.startsWith("🧩") || text.startsWith("Sticker") -> "sticker"
        else -> "text"
    }

    private fun messageId(sbn: StatusBarNotification?, inbound: Inbound): String {
        val raw = listOf(
            sbn?.packageName.orEmpty(),
            sbn?.key.orEmpty(),
            inbound.postTime.toString(),
            inbound.senderName,
            inbound.text,
        ).joinToString("|")
        return "wa-" + hash(raw)
    }

    private fun hash(value: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            digest.joinToString("") { "%02x".format(it) }.take(24)
        } catch (t: Throwable) {
            Integer.toHexString(value.hashCode())
        }
    }
}
