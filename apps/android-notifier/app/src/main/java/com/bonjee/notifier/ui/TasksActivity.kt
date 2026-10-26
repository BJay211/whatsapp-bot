package com.bonjee.notifier.ui

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
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.R
import com.bonjee.notifier.databinding.ActivityTasksBinding
import com.bonjee.notifier.databinding.ItemTaskBinding
import com.bonjee.notifier.tasks.LocalTask
import com.bonjee.notifier.tasks.LocalTaskStore
import com.bonjee.notifier.whatsapp.JarvisApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native task list.
 *
 * Everything is local first. "SEND PENDING" is the only network action: it
 * posts one `task.create` per open task that has not been accepted yet, and
 * records the real HTTP outcome. A missing endpoint leaves the task local and
 * shows the 404 instead of marking it synced.
 */
class TasksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTasksBinding
    private lateinit var store: LocalTaskStore
    private lateinit var api: JarvisApi
    private lateinit var prefs: Prefs

    private var all: List<LocalTask> = emptyList()
    private var shown: List<LocalTask> = emptyList()
    private val dateFormat = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = shown[position].id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = if (convertView == null) {
                ItemTaskBinding.inflate(layoutInflater, parent, false)
            } else {
                ItemTaskBinding.bind(convertView)
            }
            val task = shown[position]
            row.chkTaskDone.isChecked = task.done
            row.txtTaskTitle.text = task.title
            row.txtTaskTitle.setTextColor(
                ContextCompat.getColor(
                    this@TasksActivity,
                    if (task.done) R.color.text_dim else R.color.text_primary,
                ),
            )
            row.txtTaskMeta.text = buildString {
                append(if (task.done) "done" else "open")
                if (task.due.isNotBlank()) append("  ·  due ").append(task.due)
                append("  ·  ").append(if (task.synced) "workflow: accepted" else "workflow: not sent")
                if (task.syncNote.isNotBlank() && !task.synced) {
                    append(" (").append(task.syncNote.take(80)).append(")")
                }
            }
            row.root.alpha = if (task.done) 0.6f else 1f
            return row.root
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTasksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        store = LocalTaskStore(this)
        api = JarvisApi(this)

        binding.listTasks.adapter = adapter
        binding.btnAddTask.setOnClickListener { addTask() }
        binding.btnSyncTasks.setOnClickListener { syncPending() }
        binding.btnClearDone.setOnClickListener { clearDone() }

        binding.listTasks.setOnItemClickListener { _, _, position, _ ->
            val task = shown.getOrNull(position) ?: return@setOnItemClickListener
            store.toggle(task.id)
            refresh()
        }
        binding.listTasks.setOnItemLongClickListener { _, _, position, _ ->
            val task = shown.getOrNull(position) ?: return@setOnItemLongClickListener false
            AlertDialog.Builder(this)
                .setTitle("Delete this task?")
                .setMessage(task.title)
                .setPositiveButton("Delete") { _, _ ->
                    store.remove(task.id)
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        binding.txtTaskFooter.text =
            "Tasks are stored only on this phone. SEND PENDING calls task.create once per open task; " +
                "if the workflow is not deployed you will see its real error here."
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        all = store.load()
        shown = all
        adapter.notifyDataSetChanged()
        val open = all.count { !it.done }
        val done = all.count { it.done }
        val unsent = all.count { !it.done && !it.synced }
        binding.txtTaskStatus.text =
            "$open open  ·  $done done  ·  $unsent not sent\nendpoint: ${prefs.appApiUrl()}"
        if (all.isEmpty()) {
            binding.txtTaskStatus.append("\nNo tasks yet. Tap ADD TASK, or say \"task\" once the workflow is updated.")
        }
    }

    private fun addTask() {
        val pad = (14 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val title = EditText(this).apply {
            hint = "What needs doing?"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(ContextCompat.getColor(this@TasksActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@TasksActivity, R.color.text_dim))
            setBackgroundColor(ContextCompat.getColor(this@TasksActivity, R.color.bg_card_alt))
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        val due = EditText(this).apply {
            hint = "Due (optional, for example 2026-11-02 09:00)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(ContextCompat.getColor(this@TasksActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@TasksActivity, R.color.text_dim))
            setBackgroundColor(ContextCompat.getColor(this@TasksActivity, R.color.bg_card_alt))
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        container.addView(title)
        container.addView(due)

        AlertDialog.Builder(this)
            .setTitle("Add task")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val text = title.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    Toast.makeText(this, "Nothing added - the title was empty", Toast.LENGTH_SHORT).show()
                } else {
                    store.add(text, due.text?.toString().orEmpty())
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun syncPending() {
        val pending = all.filter { !it.done && !it.synced }
        if (pending.isEmpty()) {
            Toast.makeText(this, "Every open task has already been sent", Toast.LENGTH_SHORT).show()
            return
        }
        binding.txtTaskStatus.text = "Sending ${pending.size} task(s) to ${prefs.appApiUrl()} ..."
        lifecycleScope.launch {
            var accepted = 0
            var failed = 0
            var lastError = ""
            withContext(Dispatchers.IO) {
                for (task in pending) {
                    val result = api.createTask(task.title, task.due)
                    if (result.ok) {
                        accepted++
                        store.markSynced(task.id, "Accepted (HTTP ${result.code})")
                    } else {
                        failed++
                        lastError = result.userMessage()
                        store.markSynced(task.id, lastError)
                    }
                }
            }
            refresh()
            binding.txtTaskStatus.text = buildString {
                appendLine("Sync finished: $accepted accepted, $failed failed.")
                if (lastError.isNotBlank()) appendLine(lastError)
                append("Tasks stay on this phone either way.")
            }
        }
    }

    private fun clearDone() {
        val done = all.count { it.done }
        if (done == 0) {
            Toast.makeText(this, "No completed tasks to clear", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear $done completed task(s)?")
            .setMessage("They are removed from this phone only.")
            .setPositiveButton("Clear") { _, _ ->
                store.clearDone()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("unused")
    private fun stamp(at: Long): String =
        if (at > 0) dateFormat.format(Date(at)) else "unknown time"
}
