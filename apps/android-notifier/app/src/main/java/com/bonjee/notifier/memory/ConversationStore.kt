package com.bonjee.notifier.memory

import android.content.Context
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.ai.AiClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recent spoken/written conversation, kept across activity restarts.
 *
 * This is deliberately small: the last [MAX_TURNS] exchanges are enough for
 * follow-up questions without turning the phone into a transcript archive.
 * The user can clear it from the assistant screen or voice settings.
 */
class ConversationStore(context: Context) {

    private val prefs = Prefs.get(context.applicationContext)

    data class Turn(val role: String, val text: String, val at: Long)

    fun turns(): List<Turn> {
        val raw = prefs.conversationJson
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<Turn>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val role = item.optString("role")
                val text = item.optString("text")
                if (text.isBlank()) continue
                out.add(Turn(role, text, item.optLong("at", 0L)))
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun messages(): List<AiClient.Message> =
        turns()
            .filter { it.role == "user" || it.role == "assistant" }
            .map { AiClient.Message(it.role, it.text) }

    fun add(role: String, text: String) {
        if (text.isBlank()) return
        val cleanRole = if (role == "assistant") "assistant" else "user"
        val all = turns().toMutableList()
        // Avoid duplicate consecutive turns (streaming can finish more than once).
        if (all.isNotEmpty() && all.last().role == cleanRole && all.last().text == text) return
        all.add(Turn(cleanRole, text.take(4000), System.currentTimeMillis()))
        while (all.size > MAX_TURNS * 2) all.removeAt(0)
        save(all)
    }

    fun clear() {
        prefs.conversationJson = ""
    }

    private fun save(turns: List<Turn>) {
        val array = JSONArray()
        turns.forEach { turn ->
            array.put(
                JSONObject()
                    .put("role", turn.role)
                    .put("text", turn.text)
                    .put("at", turn.at),
            )
        }
        prefs.conversationJson = array.toString()
    }

    companion object {
        /** Six exchanges of context, matching AiClient.MAX_TURNS. */
        const val MAX_TURNS = 6
    }
}
