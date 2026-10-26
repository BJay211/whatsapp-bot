package com.bonjee.notifier.device

import java.util.Locale

/**
 * Splits one sentence into several device actions and carries context between
 * them, so "open WhatsApp then message John" knows the second half belongs to
 * WhatsApp.
 *
 * Pure Kotlin on purpose: no Android imports, so the headless self-check run can
 * compile this file on a desktop JVM together with the other pure classes.
 *
 * The splitter is deliberately conservative. Plain "and" is not a separator
 * (it appears inside messages: "tell John I will come and see you"), except in
 * the one shape the user actually speaks: "open <app> and <action>".
 */
object CommandChain {

    /** More than this many steps in one breath is more likely a mis-hearing. */
    const val MAX_STEPS = 5

    data class Plan(val steps: List<String>, val truncated: Boolean = false) {
        val chained: Boolean get() = steps.size > 1
    }

    /**
     * What the previous step was about. Empty strings mean "unknown", which is
     * different from a known-empty value, so this stays dependency-free.
     */
    data class Context(val app: String = "", val target: String = "") {
        val hasApp: Boolean get() = app.isNotEmpty()
        val hasTarget: Boolean get() = target.isNotEmpty()
    }

    // ---------------------------------------------------------------- parsing

    private val IGNORE = RegexOption.IGNORE_CASE

    /**
     * "then", "and then", "after that", "afterwards", "after which", a
     * semicolon or a newline. Commas alone are not separators.
     */
    private val SEPARATOR = Regex(
        """\s*(?:[,;]?\s*\b(?:and\s+then|then|after\s+that|afterwards|after\s+which)\b\s*[,;]?|;+|\n+)\s*""",
        IGNORE,
    )

    private val OPEN_AND = Regex(
        """^(?:please\s+)?(?:open(?:\s+up)?|launch|start|switch\s+to|go\s+to|bring\s+up|run)\s+(.+?)\s+and\s+(.+)$""",
        IGNORE,
    )

    private val NEXT = Regex("""^(.+?)\s+next\s+(.+)$""", IGNORE)

    /**
     * "next" is only a separator when an action follows it, otherwise "next
     * song" would be torn in half.
     */
    private val ACTION_START = Regex(
        """^(?:open|open\s+up|launch|start|switch\s+to|go\s+to|bring\s+up|message|text|sms|call|ring|dial|send|search|find|look\s*up|play|show|set|turn|navigate|email|mail|ask|tell|read|take|remind|whatsapp|torch|brightness|volume|mute|pause)\b""",
        IGNORE,
    )

    private val BODY_MARKERS = listOf(
        " saying ", " that says ", " with message ", " with ", " about ", " message ",
    )

    fun plan(raw: String): Plan {
        val text = raw.trim()
        if (text.isEmpty()) return Plan(emptyList())

        val pieces = ArrayList<String>()
        for (part in splitOnSeparators(clean(text))) {
            val cleaned = clean(part)
            if (cleaned.isEmpty()) continue
            pieces.addAll(splitOpenAnd(cleaned))
        }

        val steps = ArrayList<String>()
        for (piece in pieces) {
            steps.addAll(splitNext(piece))
        }

        val trimmed = steps.filter { it.isNotEmpty() }
        if (trimmed.isEmpty()) return Plan(listOf(clean(text)))
        return Plan(
            steps = trimmed.take(MAX_STEPS),
            truncated = trimmed.size > MAX_STEPS,
        )
    }

    /** Convenience for tests and for callers that only need the step list. */
    fun split(raw: String): List<String> = plan(raw).steps

    /**
     * Only cuts at a separator when an action starts on the other side.
     * Otherwise "message John saying I will call you then come home" would send
     * "I will call you" and try to run "come home".
     */
    private fun splitOnSeparators(text: String): List<String> {
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            val boundary = SEPARATOR.findAll(rest).firstOrNull { match ->
                val after = rest.substring(match.range.last + 1).trim()
                after.isNotEmpty() && startsAction(after)
            } ?: break
            val before = rest.substring(0, boundary.range.first).trim()
            if (before.isNotEmpty()) out.add(before)
            rest = rest.substring(boundary.range.last + 1).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    private fun startsAction(text: String): Boolean =
        ACTION_START.containsMatchIn(text) || isBareChatOrMessage(text)

    private fun splitOpenAnd(part: String): List<String> {
        val match = OPEN_AND.find(part) ?: return listOf(part)
        val app = match.groupValues[1].trim()
        val rest = match.groupValues[2].trim()
        if (app.isEmpty() || rest.isEmpty()) return listOf(part)
        // A URL or a message body is not "open app and ...".
        if (app.contains(".") || app.contains("/")) return listOf(part)
        if (!ACTION_START.containsMatchIn(rest) && !isBareChatOrMessage(rest)) return listOf(part)
        return listOf("open $app", rest)
    }

    private fun splitNext(part: String): List<String> {
        val match = NEXT.find(part) ?: return listOf(part)
        val first = match.groupValues[1].trim()
        val second = match.groupValues[2].trim()
        if (first.isEmpty() || second.isEmpty()) return listOf(part)
        if (!ACTION_START.containsMatchIn(second) && !isBareChatOrMessage(second)) {
            return listOf(part)
        }
        return listOf(first, second)
    }

    private fun isBareChatOrMessage(text: String): Boolean {
        val lower = text.lowercase(Locale.getDefault())
        return bareChat(lower) || bareMessage(lower)
    }

    // --------------------------------------------------------------- rewrite

    /**
     * Rewrites one step using what the previous steps established.
     *
     * Examples (context app=whatsapp, target=John):
     *   "message John"      -> "whatsapp John"
     *   "search for John"   -> "search John on whatsapp"
     *   "call him"          -> "whatsapp call John"
     *   "open the chat"     -> "whatsapp John"
     */
    fun rewrite(step: String, context: Context): String {
        var text = clean(step)
        if (context.hasTarget) text = replacePronoun(text, context.target)

        val lower = text.lowercase(Locale.getDefault())
        val app = context.app

        if (context.hasTarget && bareChat(lower)) {
            return if (app == "whatsapp" || app.isEmpty()) "whatsapp ${context.target}" else text
        }
        if (context.hasTarget && bareMessage(lower)) {
            return when (app) {
                "whatsapp" -> "whatsapp ${context.target}"
                "sms" -> "text ${context.target}"
                "email" -> "email ${context.target}"
                else -> "message ${context.target}"
            }
        }

        if (app == "whatsapp") {
            when {
                lower.startsWith("message ") ->
                    return "whatsapp " + text.substring("message ".length).trim()
                lower.startsWith("send a message to ") ->
                    return "whatsapp " + text.substring("send a message to ".length).trim()
                lower.startsWith("send message to ") ->
                    return "whatsapp " + text.substring("send message to ".length).trim()
                lower.startsWith("send a text to ") ->
                    return "whatsapp " + text.substring("send a text to ".length).trim()
                lower.startsWith("text ") ->
                    return "whatsapp " + text.substring("text ".length).trim()
                lower.startsWith("call ") ->
                    return "whatsapp " + text
                lower.startsWith("ring ") ->
                    return "whatsapp call " + text.substring("ring ".length).trim()
                lower.startsWith("video call ") ->
                    return "whatsapp " + text
                lower.startsWith("search for ") ->
                    return "search " + text.substring("search for ".length).trim() + " on whatsapp"
                lower.startsWith("find ") ->
                    return "search " + text.substring("find ".length).trim() + " on whatsapp"
                lower.startsWith("look up ") ->
                    return "search " + text.substring("look up ".length).trim() + " on whatsapp"
            }
        }
        return text
    }

    /** Pulls the person out of a step, or "" when the step is not about one. */
    fun targetOf(step: String): String {
        val text = clean(step)
        val head = headOf(text)
        for (pattern in TARGET_PATTERNS) {
            val match = pattern.find(head) ?: continue
            val name = tidyName(match.groupValues.getOrNull(1).orEmpty())
            if (isPerson(name)) return name
        }
        return ""
    }

    /** Which app a step opens, in the vocabulary [Context] uses. */
    fun appOf(step: String): String {
        val match = OPEN_APP.find(clean(step)) ?: return ""
        var target = tidyName(match.groupValues[1])
        if (target.isEmpty()) return ""
        if (target.contains(".") || target.contains("/")) return ""
        val lower = target.lowercase(Locale.getDefault())
        if (lower == "chat" || lower.startsWith("chat ") || lower == "conversation") return ""
        return when {
            lower == "whatsapp" || lower == "wa" || lower.startsWith("whatsapp ") -> "whatsapp"
            lower == "messages" || lower == "sms" || lower == "messaging" -> "sms"
            lower == "gmail" || lower == "email" || lower == "mail" -> "email"
            lower == "camera" -> "camera"
            lower == "contacts" || lower == "phone" -> "contacts"
            else -> lower
        }
    }

    /** Context after [step] has run. The previous target survives on purpose. */
    fun nextContext(step: String, previous: Context): Context {
        val text = clean(step)
        val lower = text.lowercase(Locale.getDefault())
        val opened = appOf(text)
        val inferred = when {
            lower.startsWith("whatsapp ") || lower.startsWith("wa ") -> "whatsapp"
            lower.startsWith("search ") && lower.endsWith(" on whatsapp") -> "whatsapp"
            lower.startsWith("text ") || lower.startsWith("sms ") -> "sms"
            lower.startsWith("send a text to ") || lower.startsWith("send an sms to ") -> "sms"
            lower.startsWith("email ") || lower.startsWith("mail ") -> "email"
            lower.startsWith("send an email to ") -> "email"
            else -> ""
        }
        val app = when {
            opened.isNotEmpty() -> opened
            inferred.isNotEmpty() -> inferred
            else -> previous.app
        }
        val target = targetOf(text).ifEmpty { previous.target }
        return Context(app = app, target = target)
    }

    // ---------------------------------------------------------------- helpers

    private val OPEN_APP = Regex(
        """^(?:open(?:\s+up)?|launch|start|switch\s+to|go\s+to|bring\s+up|run)\s+(?:the\s+)?(.+?)(?:\s+app)?$""",
        IGNORE,
    )

    private val TARGET_PATTERNS = listOf(
        Regex("""^(?:whatsapp|wa)\s+(?:video\s+)?call\s+(.+)$""", IGNORE),
        Regex("""^(?:call|ring|dial|phone)\s+(.+?)(?:\s+(?:on|via|in|through)\s+(?:whatsapp|the\s+phone|phone))?$""", IGNORE),
        Regex("""^(?:send\s+(?:a\s+)?whatsapp(?:\s+message)?\s+to|whatsapp)\s+(.+)$""", IGNORE),
        Regex("""^(?:send\s+(?:an?\s+)?(?:text|sms|message)\s+to|text|sms|message)\s+(.+)$""", IGNORE),
        Regex("""^(?:send\s+(?:an?\s+)?email\s+to|email|mail)\s+(.+)$""", IGNORE),
        Regex("""^(?:search|find|look\s*up)\s+(?:for\s+)?(.+?)\s+(?:on|in|via)\s+whatsapp$""", IGNORE),
        Regex("""^(?:search|find|look\s*up)\s+whatsapp\s+(?:for\s+)?(.+)$""", IGNORE),
        Regex("""^(?:open|start|show)\s+(?:the\s+)?(?:whatsapp\s+)?(?:chat|conversation)\s+(?:with|for)\s+(.+)$""", IGNORE),
        Regex("""^(?:search|find|look\s*up)\s+(?:for\s+)?(.+?)\s+(?:in|on|from)\s+(?:my\s+)?contacts?$""", IGNORE),
    )

    private fun bareChat(lower: String): Boolean =
        lower == "open the chat" || lower == "open chat" || lower == "open the conversation" ||
            lower == "open conversation" || lower == "show the chat" || lower == "show chat" ||
            lower == "the chat" || lower == "the conversation"

    private fun bareMessage(lower: String): Boolean =
        lower == "message" || lower == "send a message" || lower == "send message" ||
            lower == "text" || lower == "send a text"

    private fun replacePronoun(step: String, target: String): String {
        val head = headOf(step)
        val tail = step.substring(head.length)
        val replaced = Regex("""\b(?:him|her|them)\b""", IGNORE).replace(head, target)
        return replaced + tail
    }

    /** The command part before any message body. */
    private fun headOf(step: String): String {
        val lower = step.lowercase(Locale.getDefault())
        var cut = step.length
        for (marker in BODY_MARKERS) {
            val at = lower.indexOf(marker)
            if (at in 1 until cut) cut = at
        }
        return step.substring(0, cut)
    }

    private fun tidyName(raw: String): String {
        var name = raw.trim()
        name = name.removeSuffix(" please").trim()
        name = name.removeSuffix(" on whatsapp").trim()
        name = name.removeSuffix(" on the phone").trim()
        name = name.removeSuffix(" app").trim()
        name = name.trim('"', '\'', ',', '.', '?', '!', ':')
        if (name.startsWith("the ", ignoreCase = true)) name = name.substring(4).trim()
        return name
    }

    private fun isPerson(name: String): Boolean {
        if (name.isEmpty()) return false
        val lower = name.lowercase(Locale.getDefault())
        if (lower in GENERIC) return false
        if (lower.endsWith(" settings") || lower.startsWith("settings")) return false
        return true
    }

    private val GENERIC = setOf(
        "whatsapp", "wa", "chat", "the chat", "conversation", "a contact", "the contact",
        "contact", "my contacts", "contacts", "someone", "somebody", "anyone", "him", "her",
        "them", "it", "me", "you", "today", "tomorrow", "the app", "app", "phone", "camera",
        "email", "sms", "text", "message", "settings",
    )

    /**
     * Normalises one step: collapse spaces, drop "jarvis"/"please" and trailing
     * punctuation - but never the punctuation at the end of a message body,
     * because "message John saying see you at 5." must keep its full stop.
     */
    fun clean(raw: String): String {
        var text = raw.trim().replace(Regex("""\s+"""), " ")
        text = text.replace(Regex("""^(?:hey\s+|ok\s+|okay\s+)?jarvis[,:]?\s+""", IGNORE), "")
        text = text.replace(Regex("""^(?:and|also|now|please)[, ]+""", IGNORE), "")
        val hasBody = BODY_MARKERS.any { text.lowercase(Locale.getDefault()).contains(it) }
        if (!hasBody) text = text.trimEnd('.', ',', ';', ':', '!')
        return text.trim()
    }
}
