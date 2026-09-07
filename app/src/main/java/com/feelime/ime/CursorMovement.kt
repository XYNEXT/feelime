package com.feelime.ime

/** A full editor snapshot used to calculate a cursor scrub target off-main. */
data class CursorSnapshot(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val startOffset: Int = 0,
)

/**
 * Builds a local snapshot from the older text-before/text-after InputConnection
 * APIs. Those APIs do not include an absolute offset, so the current host
 * selection supplied by onUpdateSelection is required to place this window.
 *
 * A non-empty selection needs its real contents when a queued direction
 * changes after the initial collapse. Otherwise a reverse move could walk
 * through a surrogate pair in the selected text one UTF-16 unit at a time.
 */
fun cursorSnapshotAroundSelection(
    before: String,
    after: String,
    selectionStart: Int,
    selectionEnd: Int,
    selectedText: String? = null,
    maxSelectionChars: Int = 2048,
): CursorSnapshot? {
    if (selectionStart < 0 || selectionEnd < 0) return null
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)
    if (before.length > start || end - start > maxSelectionChars) return null
    val selected = if (start == end) {
        ""
    } else {
        selectedText?.takeIf { it.length == end - start } ?: return null
    }
    return CursorSnapshot(
        text = before + selected + after,
        selectionStart = before.length,
        selectionEnd = before.length + selected.length,
        startOffset = start - before.length,
    )
}

/**
 * An editor can report our own selection changes after a later setSelection
 * has already been issued. Keep the newest still-pending own selection as the
 * query baseline so an older callback cannot move the text window backwards.
 */
fun cursorSelectionBaseline(
    reported: Pair<Int, Int>,
    expected: List<Pair<Int, Int>>,
): Pair<Int, Int> {
    return if (reported in expected) expected.lastOrNull() ?: reported else reported
}

/**
 * Cursor movement in UTF-16 editor offsets, stepping by Unicode code point.
 * Android selections are UTF-16 offsets, while a user-visible cursor step must
 * not split an emoji surrogate pair. A non-empty selection collapses to its
 * left/right edge for the first step, like a native arrow key; any remaining
 * steps continue from that edge.
 */
object CursorMovement {
    fun target(snapshot: CursorSnapshot, delta: Int): Int? {
        if (delta == 0) return null
        val text = snapshot.text
        val start = snapshot.selectionStart
        val end = snapshot.selectionEnd
        if (start !in 0..text.length || end !in 0..text.length) return null

        var position = if (delta < 0) minOf(start, end) else maxOf(start, end)
        var steps = kotlin.math.abs(delta)
        if (start != end) {
            // The first arrow collapses a selection; only the remaining
            // scrub distance crosses code points.
            steps -= 1
            if (steps == 0) return position + snapshot.startOffset
        }
        repeat(steps) {
            position = if (delta < 0) previousBoundary(text, position)
            else nextBoundary(text, position)
        }
        return position + snapshot.startOffset
    }

    /**
     * Apply a sequence of scrub deltas to one snapshot. Keeping the original
     * text and advancing the local selection between steps preserves native
     * arrow semantics at both ends of the text (for example, +5 then -2 is
     * not equivalent to +3 when +5 hits the right boundary). The caller can
     * therefore answer a burst of queued scrub requests with one editor RPC.
     */
    fun targetSequence(snapshot: CursorSnapshot, deltas: Iterable<Int>): Int? {
        var start = snapshot.selectionStart
        var end = snapshot.selectionEnd
        var moved = false
        for (delta in deltas) {
            val local = target(CursorSnapshot(snapshot.text, start, end), delta) ?: return null
            start = local
            end = local
            moved = true
        }
        return if (moved) start + snapshot.startOffset else null
    }

    private fun previousBoundary(text: String, position: Int): Int {
        if (position <= 0) return 0
        return position - Character.charCount(text.codePointBefore(position))
    }

    private fun nextBoundary(text: String, position: Int): Int {
        if (position >= text.length) return text.length
        return position + Character.charCount(text.codePointAt(position))
    }
}
