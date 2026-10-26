package com.bonjee.notifier.whatsapp

import android.content.Context
import android.content.SharedPreferences
import com.bonjee.notifier.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for the contact rules.
 *
 * The project already stores everything in SharedPreferences, so this follows
 * the same pattern instead of introducing Room. All reads/writes go through the
 * pure [ContactRules] value, which means the dedupe and safety rules apply to
 * data loaded from disk as well as to user edits.
 *
 * The backend remains the source of truth for server-side authorisation; local
 * storage only mirrors what the user configured on this device.
 */
class ContactRulesStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = Prefs.get(app)
    private val sp: SharedPreferences = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): ContactRules {
        val raw = sp.getString(KEY_RULES, "") ?: ""
        if (raw.isBlank()) return ContactRules()
        return try {
            val array = JSONArray(raw)
            val parsed = ArrayList<ContactRule>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val rule = fromJson(o) ?: continue
                parsed.add(rule)
            }
            ContactRules(parsed)
        } catch (t: Throwable) {
            // Corrupt/partial write: start clean rather than crash the listener.
            ContactRules()
        }
    }

    @Synchronized
    fun save(rules: ContactRules) {
        val array = JSONArray()
        rules.items.forEach { array.put(toJson(it)) }
        sp.edit().putString(KEY_RULES, array.toString()).apply()
    }

    fun upsert(rule: ContactRule): ContactRules {
        val rules = load().upsert(rule.copy(updatedAt = rule.updatedAt.ifZeroNow()), prefs.defaultCountryCode)
        save(rules)
        return rules
    }

    fun remove(rawNumber: String): ContactRules {
        val rules = load().remove(rawNumber, prefs.defaultCountryCode)
        save(rules)
        return rules
    }

    fun setCategory(rawNumber: String, category: ContactCategory, name: String = ""): ContactRules {
        val rules = load().setCategory(
            rawNumber = rawNumber,
            category = category,
            name = name,
            at = System.currentTimeMillis(),
            defaultCountryCode = prefs.defaultCountryCode,
        )
        save(rules)
        return rules
    }

    fun toggleAutoReply(rawNumber: String, enabled: Boolean, mode: AutoReplyMode): ContactRules {
        val rules = load().toggleAutoReply(
            rawNumber = rawNumber,
            enabled = enabled,
            mode = mode,
            at = System.currentTimeMillis(),
            defaultCountryCode = prefs.defaultCountryCode,
        )
        save(rules)
        return rules
    }

    fun find(rawNumber: String?): ContactRule? = load().find(rawNumber, prefs.defaultCountryCode)

    fun findByName(name: String?): ContactRule? {
        val wanted = name?.trim().orEmpty()
        if (wanted.isEmpty()) return null
        return load().items.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    }

    fun ruleFor(rawNumber: String?, name: String = ""): ContactRule? {
        val found = load().ruleFor(rawNumber, prefs.defaultCountryCode) ?: return null
        return if (found.name.isBlank() && name.isNotBlank()) found.copy(name = name) else found
    }

    /** Folds a backend snapshot in without letting stale rows override local edits. */
    fun mergeRemote(remote: List<ContactRule>): ContactRules {
        val merged = load().mergeRemote(remote, prefs.defaultCountryCode)
        save(merged)
        return merged
    }

    /** Writes the numbers the workflow said are authorised for auto-reply. */
    fun saveServerAuthorized(numbers: Collection<String>) {
        prefs.serverAuthorizedNumbers = numbers
            .map { PhoneNumber.normalize(it, prefs.defaultCountryCode) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun Long.ifZeroNow(): Long = if (this <= 0L) System.currentTimeMillis() else this

    private fun toJson(rule: ContactRule): JSONObject = JSONObject().apply {
        put("number", rule.number)
        put("name", rule.name)
        put("category", rule.category.wire)
        put("auto_reply", rule.autoReplyEnabled)
        put("auto_reply_mode", rule.autoReplyMode.wire)
        put("template", rule.template)
        put("announce", rule.announce)
        put("priority", rule.priority)
        put("updated_at", rule.updatedAt)
    }

    private fun fromJson(o: JSONObject): ContactRule? {
        val number = PhoneNumber.normalize(o.optString("number"), prefs.defaultCountryCode)
        if (number.isEmpty()) return null
        return ContactRule(
            number = number,
            name = o.optString("name"),
            category = ContactCategory.fromWire(o.optString("category")),
            autoReplyEnabled = o.optBoolean("auto_reply", false),
            autoReplyMode = AutoReplyMode.fromWire(o.optString("auto_reply_mode")),
            template = o.optString("template"),
            announce = o.optBoolean("announce", true),
            priority = o.optBoolean("priority", false),
            updatedAt = o.optLong("updated_at", 0L),
        ).sanitized(prefs.defaultCountryCode)
    }

    companion object {
        private const val FILE = "bonjee_whatsapp"
        private const val KEY_RULES = "contact_rules_v1"
    }
}
