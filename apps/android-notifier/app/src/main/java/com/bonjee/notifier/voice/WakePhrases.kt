package com.bonjee.notifier.voice

import java.util.Locale

/**
 * The wake phrases the user asked for, matched on normalised text so Android's
 * punctuation and casing do not matter:
 *
 *   "Hey JARVIS"                    "JARVIS"
 *   "Little help here, JARVIS"      "JARVIS, you up?"
 */
object WakePhrases {

    val ALL = listOf(
        "hey jarvis",
        "jarvis",
        "little help here jarvis",
        "jarvis you up",
        "you up jarvis",
        "ok jarvis",
        "okay jarvis",
    )

    private val whitespace = Regex("\\s+")

    fun normalize(text: String): String =
        text.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(whitespace, " ")
            .trim()

    fun matches(text: String): Boolean {
        val clean = normalize(text)
        if (clean.isEmpty()) return false
        return ALL.any { phrase ->
            clean == phrase ||
                clean.startsWith("$phrase ") ||
                clean.contains(" $phrase ") ||
                clean.endsWith(" $phrase")
        }
    }

    /** Everything after the wake phrase; empty when the wake word was said alone. */
    fun strip(text: String): String {
        var clean = normalize(text)
        for (phrase in ALL.sortedByDescending { it.length }) {
            val index = clean.indexOf(phrase)
            if (index >= 0) {
                clean = clean.removeRange(index, index + phrase.length).trim()
                return clean
            }
        }
        return clean
    }
}
