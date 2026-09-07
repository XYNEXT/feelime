package com.feelime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CursorMovementTest {
    @Test
    fun `delayed own selection callback keeps newest expected baseline`() {
        val first = 4 to 4
        val second = 2 to 2

        assertEquals(second, cursorSelectionBaseline(first, listOf(first, second)))
        assertEquals(second, cursorSelectionBaseline(second, listOf(first, second)))
        assertEquals(7 to 7, cursorSelectionBaseline(7 to 7, emptyList()))
    }

    @Test
    fun `text around cursor reconstructs absolute window offset`() {
        val snapshot = cursorSnapshotAroundSelection("ab", "😀z", 2, 2)

        assertEquals(CursorSnapshot("ab😀z", 2, 2, 0), snapshot)
        assertEquals(4, snapshot?.let { CursorMovement.target(it, 1) })
    }

    @Test
    fun `text around selection preserves collapse edges`() {
        val snapshot = cursorSnapshotAroundSelection("ab", "yz", 2, 5, "xyz")

        assertEquals(CursorSnapshot("abxyzyz", 2, 5, 0), snapshot)
        assertEquals(2, snapshot?.let { CursorMovement.target(it, -1) })
        assertEquals(5, snapshot?.let { CursorMovement.target(it, 1) })
    }

    @Test
    fun `reverse movement across selected emoji keeps surrogate pair intact`() {
        val snapshot = cursorSnapshotAroundSelection("a", "z", 1, 3, "😀")

        assertEquals(1, snapshot?.let { CursorMovement.targetSequence(it, listOf(2, -2)) })
    }

    @Test
    fun `text around cursor rejects unknown or oversized positions`() {
        assertNull(cursorSnapshotAroundSelection("abc", "z", 2, 2))
        assertNull(cursorSnapshotAroundSelection("a", "z", 1, 3))
        assertNull(cursorSnapshotAroundSelection("a", "z", 1, 3, "x"))
        assertNull(cursorSnapshotAroundSelection("", "z", 0, 2049, maxSelectionChars = 2048))
    }

    @Test
    fun `moves by code point without splitting emoji`() {
        val text = "a😀b"
        assertEquals(1, CursorMovement.target(CursorSnapshot(text, 3, 3), -1))
        assertEquals(3, CursorMovement.target(CursorSnapshot(text, 1, 1), 1))
        assertEquals(4, CursorMovement.target(CursorSnapshot(text, 3, 3), 1))
        assertEquals(3, CursorMovement.target(CursorSnapshot(text, 4, 4), -1))
    }

    @Test
    fun `selection collapses to edge before remaining batch steps`() {
        val text = "012345"
        assertEquals(2, CursorMovement.target(CursorSnapshot(text, 2, 5), -1))
        assertEquals(5, CursorMovement.target(CursorSnapshot(text, 2, 5), 1))
        assertEquals(1, CursorMovement.target(CursorSnapshot(text, 2, 5), -2))
        assertEquals(6, CursorMovement.target(CursorSnapshot(text, 2, 5), 2))
    }

    @Test
    fun `queued segments keep boundary semantics in one snapshot`() {
        val snapshot = CursorSnapshot("abc😀", 1, 1)
        // +8 clamps at the end, then -2 must walk back two code points from
        // that clamped position; treating this as one net +6 would be wrong.
        assertEquals(2, CursorMovement.targetSequence(snapshot, listOf(8, -2)))
        assertEquals(2, CursorMovement.targetSequence(snapshot, listOf(2, -1)))
    }

    @Test
    fun `queued sequence preserves emoji boundaries and window offset`() {
        val snapshot = CursorSnapshot("a😀b", 1, 1, 100)
        assertEquals(104, CursorMovement.targetSequence(snapshot, listOf(1, 1)))
        assertEquals(101, CursorMovement.targetSequence(snapshot, listOf(1, -1)))
    }

    @Test
    fun `window offset is added to editor target`() {
        assertEquals(103, CursorMovement.target(CursorSnapshot("a😀b", 1, 1, 100), 1))
        assertEquals(101, CursorMovement.target(CursorSnapshot("a😀b", 1, 3, 100), -1))
    }

    @Test
    fun `invalid and zero requests are rejected`() {
        assertNull(CursorMovement.target(CursorSnapshot("abc", -1, 1), 1))
        assertNull(CursorMovement.target(CursorSnapshot("abc", 1, 4), -1))
        assertNull(CursorMovement.target(CursorSnapshot("abc", 1, 1), 0))
    }
}
