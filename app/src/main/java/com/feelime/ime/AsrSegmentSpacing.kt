package com.feelime.ime

/** Adds the one separator needed between adjacent English ASR segments. */
internal object AsrSegmentSpacing {
    fun forNext(previousFinal: String, nextSegment: String): String {
        if (previousFinal.isEmpty() || nextSegment.isEmpty()) return nextSegment
        return if (previousFinal.last().isAsciiWord() && nextSegment.first().isAsciiWord()) {
            " $nextSegment"
        } else {
            nextSegment
        }
    }

    private fun Char.isAsciiWord(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
