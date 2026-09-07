package com.feelime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorSnapshotSupportTest {
    @Test
    fun `support is scoped to the connection identity`() {
        val support = CursorSnapshotSupport()
        val first = Any()
        val second = Any()

        support.markUnsupported(first, nowMs = 0L)

        assertTrue(support.isUnsupported(first))
        assertFalse(support.isUnsupported(second))
    }

    @Test
    fun `a failed probe falls back briefly and then retries`() {
        val support = CursorSnapshotSupport()
        val owner = Any()

        support.markUnsupported(owner, nowMs = 100L)

        assertEquals(CursorSnapshotSupport.Strategy.NATIVE_ARROWS, support.strategy(owner, 2_099L))
        assertEquals(CursorSnapshotSupport.Strategy.FULL_SNAPSHOT, support.strategy(owner, 2_100L))
    }

    @Test
    fun `working text fallback avoids repeated full snapshot probes`() {
        val support = CursorSnapshotSupport()
        val owner = Any()

        support.markTextFallback(owner)

        assertEquals(CursorSnapshotSupport.Strategy.TEXT_AROUND_CURSOR, support.strategy(owner))
        assertFalse(support.isUnsupported(owner))
    }

    @Test
    fun `reset allows a lifecycle to probe again`() {
        val support = CursorSnapshotSupport()
        val owner = Any()

        support.markUnsupported(owner)
        support.reset()

        assertFalse(support.isUnsupported(owner))
    }
}
