package com.bonjee.notifier.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.AskActivity
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.R
import com.bonjee.notifier.databinding.ActivityMemoryBinding
import com.bonjee.notifier.databinding.ItemMemoryBinding
import com.bonjee.notifier.memory.LocalMemoryStore
import com.bonjee.notifier.memory.MemoryNote
import com.bonjee.notifier.whatsapp.ContactCategory
import com.bonjee.notifier.whatsapp.ContactRule
import com.bonjee.notifier.whatsapp.ContactRulesStore
import com.bonjee.notifier.whatsapp.JarvisApi
import com.bonjee.notifier.whatsapp.MessageRecord
import com.bonjee.notifier.whatsapp.MessageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Memory Core: a native graph and list built from what the app itself knows.
 *
 * Three sources are merged:
 *  - contact rules (Important / Auto-Reply / Other),
 *  - WhatsApp messages captured by the notification listener,
 *  - notes the user writes here.
 *
 * "SEARCH N8N" is a separate, explicit action: it calls `memory.search` and
 * prints the workflow's real answer or real HTTP error. The local graph is
 * never presented as the cloud archive.
 */
class MemoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMemoryBinding
    private lateinit var notes: LocalMemoryStore
    private lateinit var rules: ContactRulesStore
    private lateinit var messages: MessageStore
    private lateinit var api: JarvisApi
    private lateinit var prefs: Prefs

    private data class Entry(
        val kind: String,
        val title: String,
        val body: String,
        val meta: String,
        val noteId: String = "",
        val messageId: String = "",
        val number: String = "",
    )

    private var allEntries: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private val dateFormat = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = if (convertView == null) {
                ItemMemoryBinding.inflate(layoutInflater, parent, false)
            } else {
                ItemMemoryBinding.bind(convertView)
            }
            val entry = shown[position]
            row.txtMemoryItemKind.text = entry.kind
            row.txtMemoryItemKind.setTextColor(
                ContextCompat.getColor(
                    this@MemoryActivity,
                    when (entry.kind) {
                        "IMPORTANT" -> R.color.orange
                        "AUTO-REPLY" -> R.color.green
                        "MESSAGE" -> R.color.purple_bright
                        "NOTE" -> R.color.cyan
                        else -> R.color.text_tertiary
                    },
                ),
            )
            row.txtMemoryItemTitle.text = entry.title
            row.txtMemoryItemBody.text = entry.body.ifBlank { "(empty)" }
            row.txtMemoryItemMeta.text = entry.meta
            return row.root
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        notes = LocalMemoryStore(this)
        rules = ContactRulesStore(this)
        messages = MessageStore(this)
        api = JarvisApi(this)

        binding.listMemory.adapter = adapter
        binding.btnAddMemory.setOnClickListener { addNote() }
        binding.btnMemoryCloud.setOnClickListener { searchCloud() }
        binding.btnMemoryAsk.setOnClickListener { askJarvis() }

        binding.editMemorySearch.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    filter(s?.toString().orEmpty())
                }
            },
        )

        binding.listMemory.setOnItemLongClickListener { _, _, position, _ ->
            val entry = shown.getOrNull(position) ?: return@setOnItemLongClickListener false
            when {
                entry.noteId.isNotEmpty() -> confirmDelete(entry)
                entry.messageId.isNotEmpty() -> startActivity(
                    Intent(this, ReplyActivity::class.java)
                        .putExtra(ReplyActivity.EXTRA_MESSAGE_ID, entry.messageId),
                )
                entry.number.isNotEmpty() -> startActivity(Intent(this, ContactRulesActivity::class.java))
            }
            true
        }

        binding.memoryGraph.onNodeTap = { node ->
            AlertDialog.Builder(this)
                .setTitle(node.label)
                .setMessage(
                    if (node.detail.isBlank()) {
                        "This node has no extra detail."
                    } else {
                        node.detail
                    },
                )
                .setPositiveButton("Close", null)
                .show()
        }

        binding.txtMemoryFooter.text =
            "The graph is local and works offline. SEARCH N8N sends one memory.search " +
                "request; if the endpoint is not deployed the HTTP error is shown, never a fake answer."
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val noteList = notes.load()
        val ruleList = rules.load().items.sortedBy { it.category.ordinal }
        val messageList = messages.load().recent(60)

        allEntries = buildList {
            noteList.forEach { note ->
                add(
                    Entry(
                        kind = "NOTE",
                        title = note.title,
                        body = note.text,
                        meta = "saved " + stamp(note.createdAt),
                        noteId = note.id,
                    ),
                )
            }
            ruleList.forEach { rule ->
                add(ruleEntry(rule))
            }
            messageList.forEach { record ->
                add(
                    Entry(
                        kind = "MESSAGE",
                        title = record.displaySender,
                        body = record.text.ifBlank { "(${record.messageType})" },
                        meta = stamp(record.receivedAt) + " · " + record.state.label + " · " +
                            record.category.label,
                        messageId = record.messageId,
                    ),
                )
            }
        }

        binding.txtMemoryStatus.text = buildString {
            appendLine("contacts ${ruleList.size}  ·  messages ${messageList.size}  ·  notes ${noteList.size}")
            appendLine("notification access: " + if (com.bonjee.notifier.NotificationListener.isEnabled(this@MemoryActivity)) "granted" else "NOT granted")
            append("cloud endpoint: ").append(prefs.appApiUrl())
        }

        buildGraph(ruleList, messageList, noteList)
        filter(binding.editMemorySearch.text?.toString().orEmpty())
        if (allEntries.isEmpty()) {
            binding.txtMemoryStatus.append(
                "\nNo local memory yet. Add a note, or receive a WhatsApp message.",
            )
        }
    }

    private fun ruleEntry(rule: ContactRule): Entry {
        val autoReply = if (rule.autoReplyEnabled && rule.category == ContactCategory.AUTO_REPLY) {
            " · auto-reply on (" + rule.autoReplyMode.wire + ")"
        } else {
            " · auto-reply off"
        }
        return Entry(
            kind = when (rule.category) {
                ContactCategory.IMPORTANT -> "IMPORTANT"
                ContactCategory.AUTO_REPLY -> "AUTO-REPLY"
                ContactCategory.OTHER -> "CONTACT"
            },
            title = rule.name.ifBlank { rule.number },
            body = rule.number + "  ·  " + rule.category.label + autoReply,
            meta = "updated " + stamp(rule.updatedAt),
            number = rule.number,
        )
    }

    private fun buildGraph(
        ruleList: List<ContactRule>,
        messageList: List<MessageRecord>,
        noteList: List<MemoryNote>,
    ) {
        val nodes = ArrayList<MemoryGraphView.Node>()
        nodes.add(MemoryGraphView.Node("you", "YOU", MemoryGraphView.Kind.CENTER, "Everything below is local to this phone."))
        val edges = ArrayList<MemoryGraphView.Edge>()

        val ruleIndex = HashMap<String, Int>()
        ruleList.take(12).forEach { rule ->
            val index = nodes.size
            nodes.add(
                MemoryGraphView.Node(
                    id = "rule:" + rule.number,
                    label = rule.name.ifBlank { rule.number },
                    kind = when (rule.category) {
                        ContactCategory.IMPORTANT -> MemoryGraphView.Kind.IMPORTANT
                        ContactCategory.AUTO_REPLY -> MemoryGraphView.Kind.AUTO_REPLY
                        ContactCategory.OTHER -> MemoryGraphView.Kind.OTHER
                    },
                    detail = rule.number + "\n" + rule.category.label +
                        if (rule.autoReplyEnabled) "\nauto-reply: " + rule.autoReplyMode.wire else "",
                ),
            )
            ruleIndex[rule.number] = index
            edges.add(MemoryGraphView.Edge(0, index))
        }

        messageList.take(10).forEach { record ->
            val index = nodes.size
            nodes.add(
                MemoryGraphView.Node(
                    id = "msg:" + record.messageId,
                    label = record.senderName.ifBlank { record.displaySender },
                    kind = MemoryGraphView.Kind.MESSAGE,
                    detail = record.displaySender + "\n" + record.text.take(280) + "\n" + record.state.label,
                ),
            )
            val owner = ruleIndex[record.sender]
            edges.add(MemoryGraphView.Edge(owner ?: 0, index))
        }

        noteList.take(8).forEach { note ->
            val index = nodes.size
            nodes.add(
                MemoryGraphView.Node(
                    id = "note:" + note.id,
                    label = note.title,
                    kind = MemoryGraphView.Kind.NOTE,
                    detail = note.text.take(280) + "\n" + stamp(note.createdAt),
                ),
            )
            edges.add(MemoryGraphView.Edge(0, index))
        }

        binding.memoryGraph.setGraph(nodes, edges)
    }

    private fun filter(query: String) {
        val needle = query.trim().lowercase(Locale.getDefault())
        shown = if (needle.isEmpty()) {
            allEntries
        } else {
            allEntries.filter {
                it.title.lowercase(Locale.getDefault()).contains(needle) ||
                    it.body.lowercase(Locale.getDefault()).contains(needle) ||
                    it.meta.lowercase(Locale.getDefault()).contains(needle) ||
                    it.kind.lowercase(Locale.getDefault()).contains(needle)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun addNote() {
        val pad = (14 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val body = EditText(this).apply {
            hint = "Fact, preference or something to remember"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            setTextColor(ContextCompat.getColor(this@MemoryActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MemoryActivity, R.color.text_dim))
            setBackgroundColor(ContextCompat.getColor(this@MemoryActivity, R.color.bg_card_alt))
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        val about = EditText(this).apply {
            hint = "About (optional: a name or topic)"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setTextColor(ContextCompat.getColor(this@MemoryActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MemoryActivity, R.color.text_dim))
            setBackgroundColor(ContextCompat.getColor(this@MemoryActivity, R.color.bg_card_alt))
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setSingleLine(true)
        }
        container.addView(body)
        container.addView(about)

        AlertDialog.Builder(this)
            .setTitle("Add memory")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val text = body.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    Toast.makeText(this, "Nothing saved - the note was empty", Toast.LENGTH_SHORT).show()
                } else {
                    notes.add(text, about.text?.toString().orEmpty())
                    refresh()
                    Toast.makeText(this, "Saved on this phone", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(entry: Entry) {
        AlertDialog.Builder(this)
            .setTitle("Delete this note?")
            .setMessage(entry.body)
            .setPositiveButton("Delete") { _, _ ->
                notes.remove(entry.noteId)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun searchCloud() {
        val query = binding.editMemorySearch.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            Toast.makeText(this, "Type what to search for first", Toast.LENGTH_SHORT).show()
            return
        }
        binding.txtMemoryStatus.text = "Sending memory.search to ${prefs.appApiUrl()} ..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { api.memorySearch(query, 10) }
            binding.txtMemoryStatus.text = buildString {
                appendLine("memory.search · request ${result.requestId}")
                if (result.ok) {
                    appendLine("HTTP ${result.code} - the workflow answered.")
                    append(result.body.take(1500).ifBlank { "(empty body)" })
                } else {
                    append(result.userMessage())
                    appendLine()
                    append("Nothing was added to the local graph.")
                }
            }
        }
    }

    private fun askJarvis() {
        val query = binding.editMemorySearch.text?.toString()?.trim().orEmpty()
        val intent = Intent(this, AskActivity::class.java)
        intent.putExtra(
            AskActivity.EXTRA_QUERY,
            query.ifEmpty { "What do you remember about me?" },
        )
        startActivity(intent)
    }

    private fun stamp(at: Long): String =
        if (at > 0) dateFormat.format(Date(at)) else "unknown time"
}
