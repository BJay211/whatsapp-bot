package com.bonjee.notifier.laptop

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last few laptop commands and what actually happened, kept on the phone.
 *
 * This is a convenience log, not a source of truth: it says what this app saw,
 * which is why each row keeps the exit code and the request id. Nothing is sent
 * back to the laptop or to n8n.
 */
class LaptopHistoryStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class Entry(
        val at: Long,
        val command: String,
        val ok: Boolean,
        val exitCode: Int,
        val note: String,
        val requestId: String,
    )

    @Synchronized
    fun load(): List<Entry> {
        val raw = sp.getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<Entry>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val command = item.optString("command")
                if (command.isBlank()) continue
                out.add(
                    Entry(
                        at = item.optLong("at", 0L),
                        command = command,
                        ok = item.optBoolean("ok", false),
                        exitCode = item.optInt("exit", -1),
                        note = item.optString("note"),
                        requestId = item.optString("request_id"),
                    ),
                )
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    fun add(entry: Entry): List<Entry> {
        val list = load().toMutableList()
        list.add(0, entry)
        val trimmed = list.take(MAX_ENTRIES)
        save(trimmed)
        return trimmed
    }

    @Synchronized
    fun clear() {
        sp.edit().remove(KEY_HISTORY).apply()
    }

    private fun save(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("at", entry.at)
                    put("command", entry.command.take(400))
                    put("ok", entry.ok)
                    put("exit", entry.exitCode)
                    put("note", entry.note.take(400))
                    put("request_id", entry.requestId)
                },
            )
        }
        sp.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    companion object {
        private const val FILE = "bonjee_laptop"
        private const val KEY_HISTORY = "history_v1"
        private const val MAX_ENTRIES = 40

        fun from(run: LaptopClient.Run, at: Long = System.currentTimeMillis()): Entry = Entry(
            at = at,
            command = run.command,
            ok = run.ok,
            exitCode = run.exitCode,
            note = when {
                run.timedOut -> "timed out"
                run.ok -> "exit 0"
                run.httpCode == 0 -> run.message.take(200)
                else -> "exit ${run.exitCode}"
            },
            requestId = run.requestId,
        )
    }
}
