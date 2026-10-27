package com.bonjee.notifier.laptop

import java.util.Locale

/**
 * Turns a spoken/typed sentence into a laptop command and back.
 *
 * Pure Kotlin: no Android imports, so the headless self-check run can compile and
 * exercise it on a desktop JVM.
 *
 * Two shapes are accepted:
 *   "on my laptop open_app app=notepad"
 *   "open notepad on my laptop"
 *
 * The prefix is what tells the assistant the sentence is for the computer rather
 * than the phone. Without it, "open notepad" would open the phone's app - the
 * distinction has to be explicit, never guessed.
 */
object LaptopCommand {

    /** A parsed request. [raw] is always exactly what should be shown/approved. */
    data class Parsed(
        val name: String,
        val args: Map<String, String>,
        val raw: String,
    ) {
        val hasArgs: Boolean get() = args.isNotEmpty()
    }

    /**
     * Prefixes that hand a command to the laptop. Longest first so
     * "on my laptop" is not mistaken for "laptop".
     */
    private val PREFIXES = listOf(
        "on my laptop", "on the laptop", "on this laptop", "on laptop",
        "on my pc", "on the pc", "on this pc", "on pc",
        "on my computer", "on the computer", "on computer",
        "from my laptop", "from the laptop", "from my pc", "from the pc",
        "on my machine", "on the machine",
        "laptop:", "pc:", "computer:", "laptop,", "pc,",
        "laptop", "pc", "computer",
    )

    private val SUFFIXES = listOf(
        "on my laptop", "on the laptop", "on this laptop", "on laptop",
        "on my pc", "on the pc", "on this pc", "on pc",
        "on my computer", "on the computer", "on computer",
        "on my machine", "on the machine",
    )

    /**
     * Returns the laptop part of [text], or null when the sentence is not
     * addressed to the computer.
     */
    fun stripPrefix(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase(Locale.getDefault())

        for (prefix in PREFIXES) {
            if (!lower.startsWith(prefix)) continue
            // Word boundary: "laptops" or "pcx" must not match "laptop"/"pc".
            val next = lower.getOrNull(prefix.length)
            if (next != null && next.isLetterOrDigit()) continue
            val rest = trimmed.substring(prefix.length).trimStart(' ', ',', ':', '-', '\t')
            if (rest.isNotBlank()) return rest
        }

        for (suffix in SUFFIXES) {
            if (!lower.endsWith(suffix)) continue
            val before = lower.getOrNull(lower.length - suffix.length - 1)
            if (before != null && before.isLetterOrDigit()) continue
            val rest = trimmed.substring(0, trimmed.length - suffix.length).trimEnd(' ', ',', ':', '-', '\t')
            if (rest.isNotBlank() && isLikelyCommand(rest)) return rest
        }
        return null
    }

    /** Loose check so "open notepad on my laptop" is a command, not a question. */
    private fun isLikelyCommand(text: String): Boolean {
        val lower = text.lowercase(Locale.getDefault()).trim()
        return lower.isNotEmpty() && !lower.endsWith("?") &&
            !lower.startsWith("what ") && !lower.startsWith("how ") &&
            !lower.startsWith("why ") && !lower.startsWith("who ") &&
            !lower.startsWith("when ") && !lower.startsWith("where ") &&
            !lower.startsWith("is ") && !lower.startsWith("are ")
    }

    /**
     * Splits `open_app app=notepad path="C:\My Files"` into a name and arguments.
     * Quotes are honoured so a path with spaces stays one value.
     */
    fun parse(inner: String): Parsed {
        val raw = inner.trim()
        if (raw.isEmpty()) return Parsed("", emptyMap(), "")

        val tokens = tokenize(raw)
        if (tokens.isEmpty()) return Parsed("", emptyMap(), raw)

        // Arguments only exist when the first token is a bare command name and a
        // later token is key=value. Otherwise the whole sentence is free text.
        val first = tokens.first()
        val argTokens = tokens.drop(1)
        val looksLikeArgs = first.none { it == ' ' } && !first.contains('=') &&
            argTokens.isNotEmpty() && argTokens.all { it.contains('=') }

        if (!looksLikeArgs) {
            return Parsed(name = raw, args = emptyMap(), raw = raw)
        }

        val args = LinkedHashMap<String, String>()
        for (token in argTokens) {
            val at = token.indexOf('=')
            if (at <= 0) return Parsed(name = raw, args = emptyMap(), raw = raw)
            val key = token.substring(0, at).trim()
            val value = token.substring(at + 1).trim()
            if (key.isEmpty()) return Parsed(name = raw, args = emptyMap(), raw = raw)
            args[key] = value
        }
        return Parsed(name = first.trim(), args = args, raw = raw)
    }

    /** Quote-aware whitespace split. Unbalanced quotes fall back to plain split. */
    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in text) {
            when {
                quote != null && ch == quote -> quote = null
                quote != null -> current.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        out.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(ch)
            }
        }
        if (quote != null) return text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    /** One-line form for the approval dialog, with quoting restored where needed. */
    fun format(parsed: Parsed): String {
        if (parsed.name.isBlank()) return parsed.raw
        if (!parsed.hasArgs) return parsed.name
        val args = parsed.args.entries.joinToString(" ") { (key, value) ->
            if (value.contains(' ') || value.isEmpty()) "$key=\"$value\"" else "$key=$value"
        }
        return parsed.name + " " + args
    }

    /**
     * Commands that can destroy data or change the machine's security. The app
     * shows a stronger warning before running one; it never silently runs it.
     */
    private val DANGEROUS = listOf(
        "remove-item", "rmdir", "del ", "erase ", "format ", "clear-disk",
        "initialize-disk", "shutdown", "stop-computer", "restart-computer",
        "reg delete", "remove-itemproperty", "set-executionpolicy",
        "invoke-expression", "iex ", "net user", "net localgroup",
        "takeown", "icacls", "cipher /w", "schtasks", "sc delete",
        "bcdedit", "diskpart", "vssadmin", "wevtutil cl",
        "> c:\\windows", "out-file c:\\windows",
    )

    fun isDangerous(parsed: Parsed): Boolean {
        val lower = parsed.raw.lowercase(Locale.getDefault())
        return DANGEROUS.any { lower.contains(it) }
    }

    /** Commands that are harmless to repeat when a retry happens. */
    fun isReadOnly(parsed: Parsed): Boolean {
        val lower = parsed.name.lowercase(Locale.getDefault())
        return lower in setOf(
            "whoami", "hostname", "ip", "battery", "disk", "running", "echo",
            "version", "uptime", "screenshot", "health",
        )
    }
}
