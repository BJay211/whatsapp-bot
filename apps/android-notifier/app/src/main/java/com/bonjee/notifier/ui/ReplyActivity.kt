package com.bonjee.notifier.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.R
import com.bonjee.notifier.databinding.ActivityReplyBinding
import com.bonjee.notifier.whatsapp.Announcer
import com.bonjee.notifier.whatsapp.ContactRulesStore
import com.bonjee.notifier.whatsapp.ImportantNotifier
import com.bonjee.notifier.whatsapp.JarvisApi
import com.bonjee.notifier.whatsapp.MessageRecord
import com.bonjee.notifier.whatsapp.MessageState
import com.bonjee.notifier.whatsapp.MessageStore
import com.bonjee.notifier.whatsapp.ReplyFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The reply decision screen.
 *
 * Design guarantees enforced here:
 *  - reading, opening, or typing instructions never sends;
 *  - APPROVE & SEND shows the exact final text in a confirmation dialog;
 *  - the send status comes from the workflow, and a timeout is resolved with
 *    CHECK STATUS, never by sending the same text again;
 *  - REPLY MYSELF opens WhatsApp and never calls the AI send action.
 */
class ReplyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReplyBinding
    private lateinit var prefs: Prefs
    private lateinit var store: MessageStore
    private lateinit var rules: ContactRulesStore
    private lateinit var api: JarvisApi
    private lateinit var announcer: Announcer

    private var record: MessageRecord? = null
    private var attempts = 0
    private var revisions = 0
    private var busy = false

    private val timeFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReplyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        store = MessageStore(this)
        rules = ContactRulesStore(this)
        api = JarvisApi(this)
        announcer = Announcer.get(this)

        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
        val sender = intent.getStringExtra(EXTRA_SENDER)
        val selfOnly = intent.getBooleanExtra(EXTRA_SELF_ONLY, false)

        bindActions()

        if (selfOnly) {
            // Tapping "REPLY" on the notification is the user's choice to answer
            // personally. No AI call, no send: just open the chat.
            lifecycleScope.launch {
                val found = withContext(Dispatchers.IO) { store.findOrLatest(messageId, sender) }
                if (found == null) {
                    binding.txtReplyStatus.text = "That message is no longer in local history."
                    setBusy(false)
                } else {
                    record = found
                    if (!ImportantNotifier.openWhatsApp(this@ReplyActivity, found)) {
                        Toast.makeText(
                            this@ReplyActivity,
                            "No phone number on this message. Open WhatsApp and choose the chat.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                finish()
            }
            return
        }

        binding.txtReplyStatus.text = "Loading message..."
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { store.findOrLatest(messageId, sender) }
            record = found
            if (found == null) {
                showMissing()
            } else {
                if (found.state == MessageState.FAILED) attempts = maxOf(attempts, 1)
                show(found)
                // Resolve an uncertain send automatically once, without sending.
                if (ReplyFlow.requiresStatusQuery(found.state) && api.isConfigured()) {
                    queryStatus(automatic = true)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) announcer.stop()
    }

    private fun bindActions() {
        binding.btnGenerate.setOnClickListener { generate(useInstructions = false) }
        binding.btnRegenerate.setOnClickListener { generate(useInstructions = true) }
        binding.btnApprove.setOnClickListener { confirmApprove() }
        binding.btnReadAloud.setOnClickListener { readAloud() }
        binding.btnCheckStatus.setOnClickListener { queryStatus(automatic = false) }
        binding.btnReplyMyself.setOnClickListener { replyMyself() }
        binding.btnCancelReply.setOnClickListener { confirmCancel() }
    }

    // --------------------------------------------------------------- render

    private fun show(record: MessageRecord) {
        this.record = record
        binding.txtReplySender.text = if (record.isImportant) "★ ${record.displaySender}" else record.displaySender
        val whenText = if (record.receivedAt > 0) timeFormat.format(Date(record.receivedAt)) else "unknown time"
        binding.txtReplyMeta.text =
            listOf(
                record.sender.ifBlank { "no number resolved" },
                record.category.label,
                whenText,
                record.messageType,
            ).joinToString("  ·  ")

        binding.txtReplyState.text = "STATE: ${record.state.label.uppercase()}"
        binding.txtReplyState.setTextColor(
            ContextCompat.getColor(
                this,
                when (record.state) {
                    MessageState.SENT -> R.color.green
                    MessageState.FAILED -> R.color.red
                    MessageState.PENDING_APPROVAL, MessageState.APPROVED -> R.color.amber
                    MessageState.SENDING -> R.color.cyan
                    MessageState.CANCELLED -> R.color.text_dim
                    MessageState.RECEIVED -> R.color.text_secondary
                },
            ),
        )

        binding.txtReplyIncoming.text = record.text.ifBlank { "(no text)" }
        binding.txtReplyError.text = record.error.ifBlank { "" }
        binding.txtReplyError.visibility = if (record.error.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        binding.txtReplyAudit.text = record.audit.ifBlank { "" }

        if (binding.editReplyDraft.text?.toString() != record.draft) {
            binding.editReplyDraft.setText(record.draft)
        }
        binding.editReplyInstructions.setText(record.instructions)

        updateButtons(record)
    }

    private fun showMissing() {
        binding.txtReplySender.text = "Message not available"
        binding.txtReplyMeta.text = ""
        binding.txtReplyIncoming.text = ""
        binding.txtReplyState.text = "STATE: unknown"
        binding.txtReplyStatus.text =
            "This message is no longer in the local history (it may have been cleared). " +
                "Open Messages to pick another."
        setBusy(true)
        binding.btnCheckStatus.isEnabled = false
        binding.btnReplyMyself.isEnabled = false
    }

    private fun updateButtons(record: MessageRecord) {
        val terminal = ReplyFlow.isTerminal(record.state)
        binding.btnGenerate.isEnabled = !busy && !terminal
        binding.btnRegenerate.isEnabled = !busy && !terminal
        binding.btnApprove.isEnabled = !busy && when (record.state) {
            MessageState.RECEIVED, MessageState.PENDING_APPROVAL, MessageState.APPROVED, MessageState.FAILED -> true
            else -> false
        }
        binding.btnCancelReply.isEnabled = !busy && when (record.state) {
            MessageState.RECEIVED, MessageState.PENDING_APPROVAL, MessageState.APPROVED, MessageState.FAILED -> true
            else -> false
        }
        binding.btnCheckStatus.isEnabled = !busy
        binding.btnReadAloud.isEnabled = !busy && record.draft.isNotBlank()
        binding.btnReplyMyself.isEnabled = record.sender.filter { it.isDigit() }.length >= 8
    }

    private fun setBusy(value: Boolean) {
        busy = value
        val record = record
        if (record == null) {
            listOf(
                binding.btnGenerate, binding.btnRegenerate, binding.btnApprove,
                binding.btnReadAloud, binding.btnCheckStatus, binding.btnCancelReply,
            ).forEach { it.isEnabled = !value }
            binding.btnReplyMyself.isEnabled = false
        } else {
            updateButtons(record)
        }
    }

    // ------------------------------------------------------------- generate

    private fun generate(useInstructions: Boolean) {
        val current = record ?: return
        if (ReplyFlow.isTerminal(current.state)) return
        val instructions = binding.editReplyInstructions.text?.toString()?.trim().orEmpty()
        if (useInstructions && instructions.isEmpty()) {
            toast("Type an instruction first, for example \"say I'll call after 5\"")
            return
        }
        if (!api.isConfigured()) {
            binding.txtReplyStatus.text = "No workflow endpoint configured. Nothing was sent."
            return
        }

        // Keep any typed instruction so a draft survives a rotation.
        val saved = current.copy(
            instructions = instructions,
            draft = binding.editReplyDraft.text?.toString().orEmpty(),
            updatedAt = System.currentTimeMillis(),
        )
        store.upsert(saved)
        record = saved
        setBusy(true)
        binding.txtReplyStatus.text = "Asking the workflow for a reply..."

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.generate(
                    record = saved,
                    instructions = instructions,
                    revision = revisions++,
                    automatic = false,
                    rule = rules.find(saved.sender),
                )
            }
            setBusy(false)
            val latest = store.find(saved.messageId) ?: saved
            if (!result.ok) {
                binding.txtReplyStatus.text = result.userMessage()
                show(latest)
                return@launch
            }
            val reply = result.proposedReply()
            if (reply.isBlank()) {
                binding.txtReplyStatus.text =
                    "The workflow accepted the request (HTTP ${result.code}) but returned no reply text. " +
                        "Nothing was sent."
                show(latest)
                return@launch
            }

            var next = latest
            if (next.state == MessageState.RECEIVED || next.state == MessageState.FAILED) {
                next = next.transition(
                    MessageState.PENDING_APPROVAL,
                    draft = reply,
                    requestId = result.requestId,
                    audit = "draft received from workflow",
                ) ?: next
            } else {
                next = next.copy(
                    state = MessageState.PENDING_APPROVAL,
                    draft = reply,
                    requestId = result.requestId,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            store.upsert(next)
            show(next)
            binding.txtReplyStatus.text =
                "Draft received and shown in full. It has NOT been sent. Read it, edit it if needed, then APPROVE & SEND."
        }
    }

    // -------------------------------------------------------------- approve

    private fun confirmApprove() {
        val current = record ?: return
        val text = binding.editReplyDraft.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast("There is no reply text to send")
            return
        }
        if (!api.isConfigured()) {
            binding.txtReplyStatus.text = "No workflow endpoint configured. Nothing was sent."
            return
        }
        val recipient = current.displaySender
        AlertDialog.Builder(this)
            .setTitle("Send this exact reply?")
            .setMessage("To: $recipient\n\n$text")
            .setPositiveButton("SEND") { _, _ -> approve(current, text) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun approve(current: MessageRecord, text: String) {
        // Walk the legal path: received/failed -> pending -> approved -> sending.
        var next = current
        if (next.state == MessageState.RECEIVED || next.state == MessageState.FAILED) {
            next = next.transition(
                MessageState.PENDING_APPROVAL,
                draft = text,
                audit = "you approved this text",
            ) ?: next
        }
        if (next.state == MessageState.PENDING_APPROVAL) {
            next = next.copy(draft = text, updatedAt = System.currentTimeMillis())
            next = next.transition(MessageState.APPROVED, draft = text, audit = "you approved this text") ?: next
        }
        if (next.state == MessageState.APPROVED) {
            next = next.transition(MessageState.SENDING, draft = text, audit = "sending approved text") ?: next
        }
        if (next.state != MessageState.SENDING) {
            binding.txtReplyStatus.text = "Cannot send from state ${current.state.label}."
            show(current)
            return
        }

        store.upsert(next)
        show(next)
        setBusy(true)
        binding.txtReplyStatus.text = "Sending the exact approved text..."

        val attempt = attempts++
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.approve(
                    record = next,
                    finalText = text,
                    attempt = attempt,
                    automatic = false,
                    rule = rules.find(next.sender),
                )
            }
            setBusy(false)
            val latest = store.find(next.messageId) ?: next

            if (!result.ok) {
                // No automatic retry: show the failure and let the user decide.
                val failed = if (ReplyFlow.canTransition(latest.state, MessageState.FAILED)) {
                    latest.transition(
                        MessageState.FAILED,
                        requestId = result.requestId,
                        error = result.userMessage(),
                        audit = "send failed: HTTP ${result.code}",
                    ) ?: latest
                } else {
                    latest.copy(error = result.userMessage(), updatedAt = System.currentTimeMillis())
                }
                store.upsert(failed)
                show(failed)
                binding.txtReplyStatus.text =
                    result.userMessage() + " Use CHECK STATUS before trying again - the message may already have gone out."
                return@launch
            }

            val remote = result.sendStatus()?.let { ReplyFlow.resolveRemoteStatus(it) }
            val target = remote ?: MessageState.SENDING
            val updated = if (ReplyFlow.canTransition(latest.state, target)) {
                latest.transition(
                    target,
                    requestId = result.requestId,
                    audit = "workflow reported ${target.wire}",
                ) ?: latest
            } else {
                latest.copy(
                    state = target,
                    requestId = result.requestId,
                    audit = "workflow reported ${target.wire}",
                    updatedAt = System.currentTimeMillis(),
                )
            }
            store.upsert(updated)
            show(updated)
            if (ReplyFlow.isTerminal(updated.state)) ImportantNotifier.cancel(this@ReplyActivity, updated.messageId)
            binding.txtReplyStatus.text = when {
                target == MessageState.SENT -> "The workflow confirmed the message was sent."
                target == MessageState.FAILED -> "The workflow reported the send failed. Nothing was retried."
                else -> "Accepted by the workflow (HTTP ${result.code}), but it has not confirmed delivery yet. " +
                    "Tap CHECK STATUS - do not resend."
            }
        }
    }

    // -------------------------------------------------------- read / status

    private fun readAloud() {
        val text = binding.editReplyDraft.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast("Nothing to read yet")
            return
        }
        announcer.speak(text)
        binding.txtReplyStatus.text = "Reading the exact text shown above. Reading it does not send it."
    }

    private fun queryStatus(automatic: Boolean) {
        val current = record ?: return
        if (!api.isConfigured()) {
            if (!automatic) binding.txtReplyStatus.text = "No workflow endpoint configured."
            return
        }
        if (!automatic) {
            // Preserve unsaved edits before refreshing the record.
            val edited = current.copy(draft = binding.editReplyDraft.text?.toString().orEmpty())
            store.upsert(edited)
            record = edited
            binding.txtReplyStatus.text = "Asking the workflow for the real send status..."
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { api.status(record ?: current) }
            if (!result.ok) {
                if (!automatic) binding.txtReplyStatus.text = result.userMessage()
                return@launch
            }
            val status = result.sendStatus()
            if (status.isNullOrBlank()) {
                if (!automatic) {
                    binding.txtReplyStatus.text =
                        "The workflow has no status for this message yet (HTTP ${result.code}). Nothing changed."
                }
                return@launch
            }
            val updated = store.applyRemoteStatus(current.messageId, status)
            if (updated == null) {
                val latest = store.find(current.messageId) ?: current
                if (!automatic) {
                    binding.txtReplyStatus.text =
                        "Workflow said \"$status\", which is not a valid next step from ${latest.state.label}. Nothing changed."
                }
                return@launch
            }
            val latest = store.find(current.messageId)
            if (latest != null) show(latest)
            if (!automatic) {
                binding.txtReplyStatus.text =
                    "Workflow status: \"$status\". Local state is now ${latest?.state?.label ?: status}."
            }
        }
    }

    // -------------------------------------------------------- self / cancel

    private fun replyMyself() {
        val current = record ?: return
        if (!ImportantNotifier.openWhatsApp(this, current)) {
            toast("No usable phone number. Open WhatsApp and pick the chat yourself.")
            return
        }
        binding.txtReplyStatus.text =
            "WhatsApp opened. JARVIS did not send anything - you are replying yourself."
    }

    private fun confirmCancel() {
        val current = record ?: return
        AlertDialog.Builder(this)
            .setTitle("Cancel this reply?")
            .setMessage("The draft will be kept locally, but no message will be sent.")
            .setPositiveButton("CANCEL REPLY") { _, _ -> cancelReply(current) }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun cancelReply(current: MessageRecord) {
        if (!ReplyFlow.canTransition(current.state, MessageState.CANCELLED)) {
            binding.txtReplyStatus.text = "Cannot cancel from state ${current.state.label}."
            return
        }
        lifecycleScope.launch {
            val result = if (api.isConfigured()) {
                withContext(Dispatchers.IO) { api.cancel(current) }
            } else {
                null
            }
            val next = current.transition(
                MessageState.CANCELLED,
                audit = "you cancelled the reply",
                error = if (result != null && !result.ok) "local cancel; backend: ${result.userMessage()}" else "",
            ) ?: current
            store.upsert(next)
            show(next)
            ImportantNotifier.cancel(this@ReplyActivity, next.messageId)
            binding.txtReplyStatus.text = when {
                result == null -> "Cancelled locally. No workflow endpoint configured."
                result.ok -> "Cancelled locally and confirmed by the workflow."
                else -> "Cancelled locally. The workflow did not confirm: ${result.userMessage()}"
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_SELF_ONLY = "self_only"
    }
}
