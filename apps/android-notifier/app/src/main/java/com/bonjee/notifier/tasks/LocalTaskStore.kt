package com.bonjee.notifier.tasks

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A task the user added on this phone. */
data class LocalTask(
    val id: String,
    val title: String,
    val due: String = "",
    val done: Boolean = false,
    val createdAt: Long = 0L,
    val completedAt: Long = 0L,
    val synced: Boolean = false,
    val syncNote: String = "",
)

/**
 * Local task list.
 *
 * Tasks work with no network and no database: they are written here first. The
 * optional "send to workflow" action calls `task.create` and records the real
 * outcome - a 404 is stored as a failed sync, never as a success.
 */
class LocalTaskStore(context: Context) {

    private val app = context.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<LocalTask> {
        val raw = sp.getString(KEY_TASKS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<LocalTask>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                if (title.isEmpty()) continue
                out.add(
                    LocalTask(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        title = title,
                        due = item.optString("due"),
                        done = item.optBoolean("done", false),
                        createdAt = item.optLong("created_at", 0L),
                        completedAt = item.optLong("completed_at", 0L),
                        synced = item.optBoolean("synced", false),
                        syncNote = item.optString("sync_note"),
                    ),
                )
            }
            out.sortedWith(compareBy<LocalTask> { it.done }.thenByDescending { it.createdAt })
        } catch (t: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    fun add(title: String, due: String = ""): List<LocalTask> {
        val clean = title.trim()
        if (clean.isEmpty()) return load()
        val tasks = load().toMutableList()
        tasks.add(
            0,
            LocalTask(
                id = UUID.randomUUID().toString(),
                title = clean.take(MAX_TITLE),
                due = due.trim(),
                createdAt = System.currentTimeMillis(),
            ),
        )
        val trimmed = tasks.take(MAX_TASKS)
        save(trimmed)
        return trimmed
    }

    @Synchronized
    fun toggle(id: String): List<LocalTask> {
        val now = System.currentTimeMillis()
        val tasks = load().map {
            if (it.id != id) {
                it
            } else {
                it.copy(done = !it.done, completedAt = if (it.done) 0L else now)
            }
        }
        save(tasks)
        return tasks
    }

    @Synchronized
    fun markSynced(id: String, note: String): List<LocalTask> {
        val tasks = load().map {
            if (it.id == id) it.copy(synced = it.synced || note.startsWith("Accepted"), syncNote = note) else it
        }
        save(tasks)
        return tasks
    }

    @Synchronized
    fun remove(id: String): List<LocalTask> {
        val kept = load().filterNot { it.id == id }
        save(kept)
        return kept
    }

    @Synchronized
    fun clearDone(): List<LocalTask> {
        val kept = load().filterNot { it.done }
        save(kept)
        return kept
    }

    private fun save(tasks: List<LocalTask>) {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject().apply {
                    put("id", task.id)
                    put("title", task.title)
                    put("due", task.due)
                    put("done", task.done)
                    put("created_at", task.createdAt)
                    put("completed_at", task.completedAt)
                    put("synced", task.synced)
                    put("sync_note", task.syncNote)
                },
            )
        }
        sp.edit().putString(KEY_TASKS, array.toString()).apply()
    }

    companion object {
        private const val FILE = "bonjee_tasks"
        private const val KEY_TASKS = "tasks_v1"
        private const val MAX_TASKS = 300
        private const val MAX_TITLE = 300
    }
}
