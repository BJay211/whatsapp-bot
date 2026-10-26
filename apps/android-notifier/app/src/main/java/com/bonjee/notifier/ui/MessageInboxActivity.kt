package com.bonjee.notifier.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.R
import com.bonjee.notifier.databinding.ActivityMessageInboxBinding
import com.bonjee.notifier.databinding.ItemMessageBinding
import com.bonjee.notifier.whatsapp.ContactCategory
import com.bonjee.notifier.whatsapp.ContactRulesStore
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
 * The local message list: what arrived, what state each reply is in, and the
 * real backend status. It reads the same [MessageStore] the notification
 * listener writes, so it still works after the app is restarted or while the
 * phone is offline.
 */
class MessageInboxActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMessageInboxBinding
    private lateinit var prefs: Prefs
    private lateinit var store: MessageStore
    private lateinit var rules: ContactRulesStore
    private lateinit var api: JarvisApi

    private var shown: List<MessageRecord> = emptyList()

    private val timeFormat = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = shown[position].messageId.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = if (convertView == null) {
                ItemMessageBinding.inflate(layoutInflater, parent, false)
            } else {
                ItemMessageBinding.bind(convertView)
            }
            val record = shown[position]
            row.txtMessageSender.text = if (record.isImportant) "★ ${record.displaySender}" else record.displaySender
            row.txtMessageText.text = record.text.ifBlank { "(no text)" }
            row.txtMessageState.text = record.state.label.uppercase()
            row.txtMessageState.setTextColor(
                ContextCompat.getColor(
                    this@MessageInboxActivity,
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
            val time = if (record.receivedAt > 0) timeFormat.format(Date(record.receivedAt)) else "unknown time"
            row.txtMessageMeta.text =
                "$time  ·  ${record.category.label}  ·  ${record.messageType}" +
                    if (record.draft.isNotBlank() && record.state != MessageState.SENT) "  ·  draft ready" else ""
            return row.root
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessageInboxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        store = MessageStore(this)
        rules = ContactRulesStore(this)
        api = JarvisApi(this)

        binding.listMessages.adapter = adapter
        binding.listMessages.setOnItemClickListener { _, _, position, _ ->
            val record = shown.getOrNull(position) ?: return@setOnItemClickListener
            startActivity(
                Intent(this, ReplyActivity::class.java)
                    .putExtra(ReplyActivity.EXTRA_MESSAGE_ID, record.messageId),
            )
        }
        binding.listMessages.setOnItemLongClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { showRowActions(it) }
            true
        }

        binding.btnInboxRules.setOnClickListener {
            startActivity(Intent(this, ContactRulesActivity::class.java))
        }
        binding.btnInboxSettings.setOnClickListener {
            startActivity(Intent(this, MessageSettingsActivity::class.java))
        }
        binding.btnInboxSync.setOnClickListener { checkInFlight() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        shown = store.load().recent(200)
        adapter.notifyDataSetChanged()
        val pending = shown.count { it.state == MessageState.PENDING_APPROVAL }
        val inFlight = shown.count { ReplyFlow.requiresStatusQuery(it.state) }
        val failed = shown.count { it.state == MessageState.FAILED }
        val endpoint = if (api.isConfigured()) prefs.appApiUrl() else "not configured"
        binding.txtInboxStatus.text =
            "captured ${shown.size}  ·  pending approval $pending  ·  in flight $inFlight  ·  failed $failed\n" +
                "endpoint: $endpoint"
        if (shown.isEmpty()) {
            binding.txtInboxStatus.append(
                "\nNo messages yet. Grant notification access, then incoming WhatsApp messages appear here.",
            )
        }
    }

    /**
     * Resolves uncertain sends by asking `reply.status`. It never resends: the
     * whole point is that a timeout must be checked, not repeated.
     */
    private fun checkInFlight() {
        val targets = store.needsStatusCheck()
        if (targets.isEmpty()) {
            Toast.makeText(this, "Nothing is waiting for a send status", Toast.LENGTH_SHORT).show()
            return
        }
        if (!api.isConfigured()) {
            binding.txtInboxStatus.text = "No endpoint configured. Cannot check send status."
            return
        }
        binding.txtInboxStatus.text = "Checking ${targets.size} in-flight send(s)..."
        lifecycleScope.launch {
            var resolved = 0
            var error = ""
            withContext(Dispatchers.IO) {
                for (record in targets) {
                    val result = api.status(record)
                    if (!result.ok) {
                        error = result.userMessage()
                        continue
                    }
                    val status = result.sendStatus()
                    if (status.isNullOrBlank()) continue
                    if (store.applyRemoteStatus(record.messageId, status) != null) resolved++
                }
            }
            binding.txtInboxStatus.text = if (error.isBlank()) {
                "Status check complete. $resolved updated."
            } else {
                "$resolved updated. Last error: $error"
            }
            refresh()
        }
    }

    private fun showRowActions(record: MessageRecord) {
        val move = arrayOf("Move to Important", "Move to Auto-Reply", "Move to Other", "Delete local copy")
        AlertDialog.Builder(this)
            .setTitle(record.displaySender)
            .setItems(move) { _, which ->
                val category = when (which) {
                    0 -> ContactCategory.IMPORTANT
                    1 -> ContactCategory.AUTO_REPLY
                    2 -> ContactCategory.OTHER
                    else -> null
                }
                if (category == null) {
                    store.remove(record.messageId)
                    refresh()
                    return@setItems
                }
                if (record.sender.isBlank()) {
                    Toast.makeText(this, "This message has no number; edit it from Message Rules", Toast.LENGTH_LONG).show()
                    return@setItems
                }
                val updated = rules.setCategory(record.sender, category, record.senderName)
                val rule = updated.find(record.sender)
                if (rule != null) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { api.updateContactSettings(rule, prefs.isServerAuthorized(rule.number)) }
                    }
                }
                // Re-tag the stored message so the list reflects the move at once.
                store.upsert(record.copy(category = category, updatedAt = System.currentTimeMillis()))
                refresh()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
