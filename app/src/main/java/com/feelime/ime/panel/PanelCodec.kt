package com.feelime.ime.panel

/**
 * Line-oriented store encoding for the clipboard/favorites panels (design
 * "剪贴板/收藏"): one item per line as `id\ttime\ttext` with the text's newlines
 * and tabs escaped. Plain Kotlin strings so the codec is unit-testable on the
 * JVM without org.json (the bridge layer converts to JSONArray at the edge).
 */
data class PanelItem(
    val id: String,
    val time: Long,
    val text: String,
    val code: String = "",
    /** 1-based candidate slot for exact code matches (default head). */
    val rank: Int = 1,
)

object PanelCodec {
    private const val RECORD_SEPARATOR = '\n'
    private const val FIELD_SEPARATOR = '\t'

    fun serialize(items: List<PanelItem>): String = items.joinToString(RECORD_SEPARATOR.toString()) { item ->
        // An optional 4th field carries the phrase input code
        // (empty for clipboard rows and for phrases without one).
        // A 5th field carries the candidate rank (default 1).
        "${item.id}$FIELD_SEPARATOR${item.time}$FIELD_SEPARATOR${escape(item.code)}$FIELD_SEPARATOR${escape(item.text)}$FIELD_SEPARATOR${item.rank}"
    }

    fun parse(raw: String): List<PanelItem> = raw.split(RECORD_SEPARATOR)
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            // Field layout by age: `id\ttime\ttext` (oldest) →
            // `id\ttime\tcode\ttext` →
        // `id\ttime\tcode\ttext\trank`. Code and text are
            // escaped, so splitting on the raw separator is unambiguous.
            val fields = line.split(FIELD_SEPARATOR)
            if (fields.size < 3 || fields[0].isEmpty()) return@mapNotNull null
            val time = fields[1].toLongOrNull() ?: return@mapNotNull null
            when (fields.size) {
                3 -> PanelItem(fields[0], time, unescape(fields[2]))
                4 -> PanelItem(fields[0], time, unescape(fields[3]), unescape(fields[2]))
                else -> PanelItem(
                    fields[0],
                    time,
                    unescape(fields[3]),
                    unescape(fields[2]),
                    fields[4].trim().toIntOrNull()?.coerceIn(1, 99) ?: 1,
                )
            }
        }

    private fun escape(text: String): String = buildString(text.length + 8) {
        for (char in text) {
            when (char) {
                '\\' -> append("\\\\")
                RECORD_SEPARATOR -> append("\\n")
                FIELD_SEPARATOR -> append("\\t")
                else -> append(char)
            }
        }
    }

    /** Single-pass decode: `\\n`/`\\t` restore separators, else `\\` is literal. */
    private fun unescape(text: String): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length) {
                when (text[index + 1]) {
                    'n' -> append(RECORD_SEPARATOR)
                    't' -> append(FIELD_SEPARATOR)
                    '\\' -> append('\\')
                    else -> append(char).append(text[index + 1])
                }
                index += 2
            } else {
                append(char)
                index += 1
            }
        }
    }
}

/**
 * Pure list algebra shared by both panels: newest-first, same-text dedupe by
 * bump-to-top, and the design §3.5 budget rule (drop oldest until under cap).
 */
object PanelStoreOps {
    /**
     * Inserts [text] at the front. Dedupe is by exact text. [textBudgetBytes]
     * bounds the sum of UTF-8 text bytes only (metadata is separate per the
     * design); items are dropped oldest-first until the budget holds.
     * Returns null when the text itself can never fit the budget.
     */
    fun insert(
        items: List<PanelItem>,
        text: String,
        id: String,
        time: Long,
        textBudgetBytes: Int,
        maxItems: Int,
    ): List<PanelItem>? {
        if (text.isEmpty()) return null
        if (text.encodeToByteArray().size > textBudgetBytes) return null
        val deduped = items.filter { it.text != text }
        val next = ArrayList<PanelItem>(deduped.size + 1)
        next.add(PanelItem(id, time, text))
        next.addAll(deduped)
        while (next.size > maxItems || budgetOf(next) > textBudgetBytes) {
            if (next.size == 1) return null
            next.removeAt(next.size - 1)
        }
        return next
    }

    fun remove(items: List<PanelItem>, id: String): List<PanelItem> = items.filterNot { it.id == id }

    /** Phrase manager: rewrite one item's text in place. Same-text
     * duplicates collapse (insert's dedupe rule); null when [id] is unknown
     * or the new text is blank. [code] rides along ("" keeps the
     * item without an input code). [rank] rides along too. */
    fun update(
        items: List<PanelItem>,
        id: String,
        text: String,
        code: String = "",
        rank: Int = 1,
    ): List<PanelItem>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || items.none { it.id == id }) return null
        val kept = items.filter { it.id != id && it.text != trimmed }
        val target = items.first { it.id == id }
        val at = items.indexOfFirst { it.id == id }.coerceAtMost(kept.size)
        return kept.subList(0, at) + target.copy(text = trimmed, code = code, rank = rank) +
            kept.subList(at, kept.size)
    }

    /** Phrase manager: reorder to [to] (0 = pin to top). */
    fun move(items: List<PanelItem>, id: String, to: Int): List<PanelItem> {
        val from = items.indexOfFirst { it.id == id }
        if (from < 0) return items
        val next = items.toMutableList()
        val item = next.removeAt(from)
        next.add(to.coerceIn(0, next.size), item)
        return next
    }

    fun clear(items: List<PanelItem>): List<PanelItem> = emptyList()

    /** The design budget: sum of item text UTF-8 sizes (metadata excluded). */
    fun budgetOf(items: List<PanelItem>): Int = items.sumOf { it.text.encodeToByteArray().size }
}
