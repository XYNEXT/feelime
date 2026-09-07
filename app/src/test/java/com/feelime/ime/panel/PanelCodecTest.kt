package com.feelime.ime.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelCodecTest {
    @Test
    fun `round trips text with newlines tabs and backslashes`() {
        val items = listOf(
            PanelItem("a1", 1L, "hello\nworld\ttab\\slash"),
            PanelItem("b2", 2L, "常用语，中文"),
            PanelItem("c3", 3L, ""),
        )
        val raw = PanelCodec.serialize(items.filter { it.text.isNotEmpty() })
        assertEquals(items.take(2), PanelCodec.parse(raw))
    }

    @Test
    fun `round trips hostile backslash sequences without corruption`() {
        // Review B3: escape-order bugs corrupt `a\tb` (literal backslash + t)
        // and Windows-style paths. Single-pass decode must restore them exactly.
        val hostile = listOf(
            "a\\tb",          // literal backslash followed by t
            "C:\\temp\\new",  // windows path
            "{\"a\":\"x\\ty\"}", // embedded json
            "\\\\n",          // double backslash then n
            "trailing\\",
        )
        val raw = PanelCodec.serialize(hostile.mapIndexed { i, text -> PanelItem("h$i", i.toLong(), text) })
        assertEquals(hostile, PanelCodec.parse(raw).map { it.text })
    }

    @Test
    fun `parse tolerates malformed lines`() {
        val parsed = PanelCodec.parse("garbage\na1\t5\tok\n\nx\tnotanumber\ttext")
        assertEquals(listOf(PanelItem("a1", 5L, "ok")), parsed)
    }

    @Test
    fun `round trips rank and defaults legacy rows to 1`() {
        // `id\ttime\tcode\ttext\trank`; rows without the 5th field
        // (and pre- 3-field rows) read rank 1; invalid values clamp.
        val items = listOf(
            PanelItem("a1", 1L, "你好", "nh", 2),
            PanelItem("b2", 2L, "无位次", "wcc"),
            PanelItem("c3", 3L, "古董行"),
        )
        val raw = PanelCodec.serialize(items)
        assertEquals(items, PanelCodec.parse(raw))
        val legacy = PanelCodec.parse("a1\t5\t旧格式\nb2\t6\tcode\t旧四段\nb3\t7\tcode\t文\t0\t尾巴")
        assertEquals(
            listOf(
                PanelItem("a1", 5L, "旧格式"),
                PanelItem("b2", 6L, "旧四段", "code"),
                PanelItem("b3", 7L, "文", "code", 1),
            ),
            legacy,
        )
    }

    @Test
    fun `insert dedupes by text and bumps to front`() {
        val items = listOf(
            PanelItem("1", 1L, "old"),
            PanelItem("2", 2L, "dup"),
        )
        val next = PanelStoreOps.insert(items, "dup", "3", 3L, 1024, 50)!!
        assertEquals(listOf("3", "1"), next.map { it.id })
    }

    @Test
    fun `insert drops oldest until item cap holds`() {
        val items = (1..50).map { PanelItem(it.toString(), it.toLong(), "t$it") }
        val next = PanelStoreOps.insert(items, "new", "51", 51L, 1024 * 1024, 50)!!
        assertEquals(50, next.size)
        assertEquals("51", next.first().id)
        // Newest-first list: the dropped one is the tail (oldest, id "50").
        assertFalse(next.any { it.id == "50" })
    }

    @Test
    fun `insert enforces utf8 text byte budget`() {
        // 1 CJK char = 3 UTF-8 bytes; each 10-char item is 30 bytes.
        val big = "汉".repeat(10)
        val items = (1..5).map { PanelItem(it.toString(), it.toLong(), big) }
        assertEquals(150, PanelStoreOps.budgetOf(items))
        val next = PanelStoreOps.insert(items, big, "6", 6L, 30, 50)!!
        // Budget 30 holds exactly one 30-byte item: everything else drops.
        assertEquals(30, PanelStoreOps.budgetOf(next))
        assertEquals(listOf("6"), next.map { it.id })
    }

    @Test
    fun `insert rejects text that can never fit`() {
        val huge = "x".repeat(57_345)
        assertNull(PanelStoreOps.insert(emptyList(), huge, "1", 1L, 57_344, 50))
        assertNull(PanelStoreOps.insert(emptyList(), "", "1", 1L, 57_344, 50))
    }

    @Test
    fun `remove and clear`() {
        val items = listOf(PanelItem("1", 1L, "a"), PanelItem("2", 2L, "b"))
        assertEquals(listOf("2"), PanelStoreOps.remove(items, "1").map { it.id })
        assertTrue(PanelStoreOps.clear(items).isEmpty())
    }
}
