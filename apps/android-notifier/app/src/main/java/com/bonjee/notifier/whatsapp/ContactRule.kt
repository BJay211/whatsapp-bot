package com.bonjee.notifier.whatsapp

/**
 * Where a WhatsApp contact sits in the user's rules.
 *
 * These are deliberately three plain buckets rather than a free-form tag list:
 * the notification/announcement behaviour and the auto-reply permission are
 * driven by the category, and "Important" must never imply "may auto-reply".
 */
enum class ContactCategory(val wire: String, val label: String) {
    IMPORTANT("important", "Important"),
    AUTO_REPLY("auto_reply", "Auto-Reply"),
    OTHER("other", "Other");

    companion object {
        /** Tolerant parser for stored JSON and backend responses. */
        fun fromWire(value: String?): ContactCategory =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: OTHER
    }
}

/**
 * How JARVIS is allowed to answer a contact unattended.
 *
 * AI is not "send anything": it asks the backend to produce/authorise a reply
 * under the workflow's own policy, and the backend decides whether to send.
 */
enum class AutoReplyMode(val wire: String) {
    OFF("off"),
    TEMPLATE("template"),
    AI("ai");

    companion object {
        fun fromWire(value: String?): AutoReplyMode =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: OFF
    }
}

/**
 * One contact rule. [number] is always stored in E.164 form - see [PhoneNumber].
 *
 * The defaults are the safe ones: a newly seen number is Other, announced only
 * if the user turned announcements on, and never auto-replied.
 */
data class ContactRule(
    val number: String,
    val name: String = "",
    val category: ContactCategory = ContactCategory.OTHER,
    val autoReplyEnabled: Boolean = false,
    val autoReplyMode: AutoReplyMode = AutoReplyMode.OFF,
    val template: String = "",
    val announce: Boolean = true,
    val priority: Boolean = false,
    val updatedAt: Long = 0L,
) {
    val displayName: String
        get() = name.trim().ifEmpty { PhoneNumber.pretty(number) }

    /**
     * Returns a safe, normalised copy, or null when the number cannot be turned
     * into E.164. The category/auto-reply invariants are enforced here so no
     * screen can create an "Other" contact that silently auto-replies:
     *
     *  - only Auto-Reply contacts may have auto-reply enabled;
     *  - mode OFF clears the enabled flag;
     *  - Important contacts get priority, and Other contacts do not.
     */
    fun sanitized(defaultCountryCode: String = PhoneNumber.DEFAULT_COUNTRY_CODE): ContactRule? {
        val n = PhoneNumber.normalize(number, defaultCountryCode)
        if (n.isEmpty()) return null
        val isAuto = category == ContactCategory.AUTO_REPLY
        val mode = if (isAuto) autoReplyMode else AutoReplyMode.OFF
        return copy(
            number = n,
            name = name.trim(),
            autoReplyEnabled = isAuto && autoReplyEnabled && mode != AutoReplyMode.OFF,
            autoReplyMode = mode,
            template = if (isAuto) template.trim() else "",
            priority = category == ContactCategory.IMPORTANT && priority,
        )
    }
}

/**
 * The whole contact rule set, as an immutable value.
 *
 * Kept as plain Kotlin (no Android, no org.json) so the dedupe/merge rules can
 * be exercised headlessly and the storage layer only has to map to/from JSON.
 * Duplicates are impossible because every mutation keys on the normalised E.164
 * number, not on the raw string the notification happened to contain.
 */
data class ContactRules(val items: List<ContactRule> = emptyList()) {

    fun find(rawNumber: String?, defaultCountryCode: String = PhoneNumber.DEFAULT_COUNTRY_CODE): ContactRule? {
        val key = PhoneNumber.normalize(rawNumber, defaultCountryCode)
        if (key.isEmpty()) return null
        return items.firstOrNull { it.number == key }
    }

    /** The stored rule, or a transient safe Other rule for a number we have never seen. */
    fun ruleFor(rawNumber: String?, defaultCountryCode: String = PhoneNumber.DEFAULT_COUNTRY_CODE): ContactRule? {
        val key = PhoneNumber.normalize(rawNumber, defaultCountryCode)
        if (key.isEmpty()) return null
        return find(key, defaultCountryCode) ?: ContactRule(number = key, category = ContactCategory.OTHER)
    }

    /**
     * Inserts or replaces the rule with the same normalised number.
     *
     * An incoming blank name never erases a known name, and the older of the two
     * `updatedAt` values is ignored so a stale sync cannot undo a user edit.
     */
    fun upsert(
        rule: ContactRule,
        defaultCountryCode: String = PhoneNumber.DEFAULT_COUNTRY_CODE,
    ): ContactRules {
        val clean = rule.sanitized(defaultCountryCode) ?: return this
        val index = items.indexOfFirst { it.number == clean.number }
        if (index < 0) return ContactRules(items + clean)
        val old = items[index]
        if (old.updatedAt > clean.updatedAt && clean.updatedAt > 0L) return this
        val merged = clean.copy(
            name = clean.name.ifBlank { old.name },
            template = clean.template.ifBlank { old.template },
        )
        return ContactRules(items.toMutableList().also { it[index] = merged })
    }

    fun remove(
        rawNumber: String?,
        defaultCountryCode: String = PhoneNumber.DEFAULT_COUNTRY_CODE,
    ): ContactRules {
        val key = PhoneNumber.normalize(rawNumber, defaultCountryCode)
        if (key.isEmpty()) return this
        return ContactRules(items.filterNot { it.number == key })
    }

    /** Promote an unknown number to Important/Auto-Reply (or demote back). */
    fun setCategory(
        rawNumber: String?,
        category: ContactCategory,
        name: String = "",
        at: Long = 0L,
        defaultCountryCode: String = PhoneNumber.DEFAULT_COUNTRY_CODE,
    ): ContactRules {
        val key = PhoneNumber.normalize(rawNumber, defaultCountryCode)
        if (key.isEmpty()) return this
        val existing = items.firstOrNull { it.number == key } ?: ContactRule(number = key, name = name)
        return upsert(
            existing.copy(
                category = category,
                name = existing.name.ifBlank { name },
                updatedAt = if (at > 0) at else existing.updatedAt,
            ),
            defaultCountryCode,
        )
    }

    fun toggleAutoReply(
        rawNumber: String?,
        enabled: Boolean,
        mode: AutoReplyMode,
        at: Long = 0L,
        defaultCountryCode: String = PhoneNumber.DEFAULT_COUNTRY_CODE,
    ): ContactRules {
        val key = PhoneNumber.normalize(rawNumber, defaultCountryCode)
        if (key.isEmpty()) return this
        val existing = items.firstOrNull { it.number == key } ?: ContactRule(number = key)
        // Enabling auto-reply on a contact that is not in the Auto-Reply bucket
        // moves it there; that is the explicit promotion the task asks for.
        val category = if (enabled && existing.category != ContactCategory.AUTO_REPLY) {
            ContactCategory.AUTO_REPLY
        } else {
            existing.category
        }
        return upsert(
            existing.copy(
                category = category,
                autoReplyEnabled = enabled,
                autoReplyMode = if (enabled) mode else AutoReplyMode.OFF,
                updatedAt = if (at > 0) at else existing.updatedAt,
            ),
            defaultCountryCode,
        )
    }

    fun byCategory(category: ContactCategory): List<ContactRule> =
        items.filter { it.category == category }

    /** Important first, then Auto-Reply, then Other; alphabetical within a group. */
    fun sortedForDisplay(): List<ContactRule> =
        items.sortedWith(
            compareBy(
                { when (it.category) {
                    ContactCategory.IMPORTANT -> 0
                    ContactCategory.AUTO_REPLY -> 1
                    ContactCategory.OTHER -> 2
                } },
                { it.displayName.lowercase() },
            ),
        )

    fun counts(): Map<ContactCategory, Int> =
        ContactCategory.entries.associateWith { c -> items.count { it.category == c } }

    /**
     * Folds a backend snapshot into local edits. Local rules are authoritative
     * for `updatedAt >= remote`, which keeps an offline edit from being lost when
     * the first sync afterwards returns an older copy.
     */
    fun mergeRemote(remote: List<ContactRule>, defaultCountryCode: String = PhoneNumber.DEFAULT_COUNTRY_CODE): ContactRules {
        var out = this
        for (rule in remote) out = out.upsert(rule, defaultCountryCode)
        return out
    }
}
