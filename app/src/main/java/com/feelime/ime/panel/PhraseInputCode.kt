package com.feelime.ime.panel

/** Default shortcut only; explicit user codes always take precedence. */
object PhraseInputCode {
    fun generate(text: String, initialOf: (Int) -> Char?): String {
        val points = text.codePoints().toArray()
        val containsMappedCharacter = points.any { initialOf(it) != null }
        val limit = if (containsMappedCharacter) 12 else 3
        val result = StringBuilder()
        for (point in points) {
            val initial = initialOf(point)
            if (initial != null) {
                result.append(initial)
            } else if (Character.isLetterOrDigit(point)) {
                result.appendCodePoint(Character.toLowerCase(point))
            }
            if (result.codePointCount(0, result.length) >= limit) break
        }
        return result.toString()
    }
}
