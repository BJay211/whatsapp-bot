package com.bonjee.notifier.voice

import java.util.Locale

/**
 * Turns model output into something worth listening to.
 *
 * Answers can contain markdown, JSON, URLs, code or metadata. Reading those
 * aloud is unpleasant and can expose internals, so they are removed before the
 * text reaches the speech engine. The on-screen answer is never modified.
 */
object SpeechText {

    private val codeFence = Regex("```[\\s\\S]*?```")
    private val inlineCode = Regex("`([^`]*)`")
    private val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val markdownLink = Regex("\\[([^]]+)]\\([^)]*\\)")
    private val heading = Regex("(?m)^#{1,6}\\s*")
    private val bullets = Regex("(?m)^\\s*[-*+]\\s+")
    private val emphasis = Regex("[*_~]{1,3}")
    private val metadataLine = Regex("(?m)^\\s*(—|--|-)\\s*(AI|source|model|tokens?)\\b.*$", RegexOption.IGNORE_CASE)
    private val jsonish = Regex("^\\s*[\\[{].*[\\]}]\\s*$", RegexOption.DOT_MATCHES_ALL)
    private val whitespace = Regex("\\s+")

    fun clean(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        // A raw JSON payload is data, not prose.
        if (jsonish.matches(text)) return "The details are on screen."

        text = codeFence.replace(text, " Code is on screen. ")
        text = markdownLink.replace(text, "$1")
        text = inlineCode.replace(text, "$1")
        text = url.replace(text, " a link ")
        text = metadataLine.replace(text, "")
        text = heading.replace(text, "")
        text = bullets.replace(text, "")
        text = emphasis.replace(text, "")
        text = text.replace("`", "")
        text = text.replace("\u2022", " ")
        text = whitespace.replace(text, " ").trim()

        // A short list of file paths or symbols reads badly; keep prose only.
        if (text.count { it == '/' || it == '\\' } > 6) {
            return "The result is on screen."
        }
        return text
    }

    /**
     * Splits a complete answer at sentence boundaries. Streaming uses
     * [SentenceBuffer] instead so partial words are never spoken.
     */
    fun sentences(raw: String): List<String> {
        val text = clean(raw)
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val builder = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            builder.append(ch)
            val boundary = (ch == '.' || ch == '!' || ch == '?' || ch == '\n') &&
                (i + 1 >= text.length || text[i + 1].isWhitespace())
            if (boundary) {
                val sentence = builder.toString().trim()
                if (sentence.length > 1) out.add(sentence)
                builder.setLength(0)
            }
            i++
        }
        val tail = builder.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)

        // Very long unpunctuated sentences are split into listenable chunks.
        return out.flatMap { chunk ->
            if (chunk.length <= MAX_SEGMENT) listOf(chunk)
            else chunk.chunked(MAX_SEGMENT).filter { it.isNotBlank() }
        }
    }

    /** True when [recognized] is just the assistant hearing its own voice. */
    fun looksLikeEcho(recognized: String, spoken: String): Boolean {
        val heard = normalize(recognized)
        val said = normalize(spoken)
        if (heard.length < 3 || said.isEmpty()) return false
        if (said.contains(heard)) return true
        // Partial recognitions often drop a word; require most words to match.
        val words = heard.split(' ').filter { it.length > 2 }
        if (words.isEmpty()) return false
        val hits = words.count { said.contains(it) }
        return hits >= maxOf(2, words.size * 3 / 4)
    }

    fun normalize(text: String): String =
        text.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(whitespace, " ")
            .trim()

    private const val MAX_SEGMENT = 220
}

/**
 * Accumulates streamed deltas and releases only complete sentences. This keeps
 * ordering correct and prevents the engine from speaking a half-word.
 */
class SentenceBuffer(private val onSentence: (String) -> Unit) {

    private val pending = StringBuilder()

    fun append(delta: String) {
        if (delta.isEmpty()) return
        pending.append(delta)
        while (true) {
            val text = pending.toString()
            val cut = lastBoundary(text)
            if (cut <= 0) return
            val sentence = SpeechText.clean(text.substring(0, cut))
            pending.delete(0, cut)
            if (sentence.isNotEmpty()) onSentence(sentence)
        }
    }

    /** Releases whatever is left, even without punctuation. */
    fun finish(): String {
        val tail = SpeechText.clean(pending.toString())
        pending.setLength(0)
        if (tail.isNotEmpty()) onSentence(tail)
        return tail
    }

    fun hasPending(): Boolean = pending.isNotBlank()

    private fun lastBoundary(text: String): Int {
        var cut = -1
        for (i in text.indices) {
            val ch = text[i]
            if ((ch == '.' || ch == '!' || ch == '?' || ch == '\n') &&
                (i + 1 >= text.length || text[i + 1].isWhitespace())
            ) {
                cut = i + 1
            }
        }
        // Do not emit a tiny fragment just because the model wrote "e.g.".
        if (cut > 0 && cut < 12) {
            val next = text.indexOf('\n', cut)
            if (next > cut) cut = next + 1
        }
        return cut
    }
}
