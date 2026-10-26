package com.bonjee.notifier.memory

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One locally stored memory fragment. */
data class MemoryNote(
    val id: String,
    val text: String,
    val about: String = "",
    val createdAt: Long = 0L,
) {
    val title: String
        get() = about.trim().ifEmpty { "General" }
}

/**
 * Local notes for the Memory Core screen.
 *
 * These are the memories the app itself can see: your own notes plus the
 * captured messages and contact rules. The cloud graph in n8n is queried
 * separately with `memory.search`, and its real answer (or real error) is shown
 * in the UI. Nothing here is uploaded.
 *
 * SharedPreferences + org.json to match the rest of the app; bounded so a long
 * life cannot grow the file without limit.
 */
class LocalMemoryStore(context: Context) {

    private val app = context.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<MemoryNote> {
        val raw = sp.getString(KEY_NOTES, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<MemoryNote>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val text = item.optString("text").trim()
                if (text.isEmpty()) continue
                out.add(
                    MemoryNote(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        text = text,
                        about = item.optString("about"),
                        createdAt = item.optLong("created_at", 0L),
                    ),
                )
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    fun add(text: String, about: String = ""): List<MemoryNote> {
        val clean = text.trim()
        if (clean.isEmpty()) return load()
        val notes = load().toMutableList()
        notes.add(
            0,
            MemoryNote(
                id = UUID.randomUUID().toString(),
                text = clean.take(MAX_TEXT),
                about = about.trim().take(MAX_ABOUT),
                createdAt = System.currentTimeMillis(),
            ),
        )
        val trimmed = notes.take(MAX_NOTES)
        save(trimmed)
        return trimmed
    }

    @Synchronized
    fun remove(id: String): List<MemoryNote> {
        val kept = load().filterNot { it.id == id }
        save(kept)
        return kept
    }

    @Synchronized
    fun clear() {
        sp.edit().remove(KEY_NOTES).apply()
    }

    private fun save(notes: List<MemoryNote>) {
        val array = JSONArray()
        notes.forEach { note ->
            array.put(
                JSONObject().apply {
                    put("id", note.id)
                    put("text", note.text)
                    put("about", note.about)
                    put("created_at", note.createdAt)
                },
            )
        }
        sp.edit().putString(KEY_NOTES, array.toString()).apply()
    }

    companion object {
        private const val FILE = "bonjee_memory"
        private const val KEY_NOTES = "notes_v1"
        private const val MAX_NOTES = 200
        private const val MAX_TEXT = 2000
        private const val MAX_ABOUT = 80
    }
}
