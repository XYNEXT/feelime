package com.feelime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrSegmentSpacingTest {
    @Test
    fun separatesAdjacentEnglishLettersAndDigits() {
        assertEquals(" Today", AsrSegmentSpacing.forNext("Monday", "Today"))
        assertEquals(" 3", AsrSegmentSpacing.forNext("v2", "3"))
    }

    @Test
    fun keepsChineseSegmentsAdjacent() {
        assertEquals("今天", AsrSegmentSpacing.forNext("昨天是", "今天"))
        assertEquals("Today", AsrSegmentSpacing.forNext("昨天是", "Today"))
    }

    @Test
    fun respectsExistingSpaceAndPunctuation() {
        assertEquals(" Today", AsrSegmentSpacing.forNext("Monday", " Today"))
        assertEquals("Today", AsrSegmentSpacing.forNext("Monday ", "Today"))
        assertEquals("Today", AsrSegmentSpacing.forNext("Monday.", "Today"))
        assertEquals("Today", AsrSegmentSpacing.forNext("Monday,", "Today"))
    }

    @Test
    fun emptySegmentsPassThrough() {
        assertEquals("", AsrSegmentSpacing.forNext("Monday", ""))
        assertEquals("Today", AsrSegmentSpacing.forNext("", "Today"))
    }

    @Test
    fun partialUpdatesAndFinalUseTheUnprefixedSegment() {
        assertEquals(" Today", AsrSegmentSpacing.forNext("Monday", "Today"))
        assertEquals(" Today is", AsrSegmentSpacing.forNext("Monday", "Today is"))
        assertEquals(" Today", AsrSegmentSpacing.forNext("Monday", "Today"))
        // A final correction can change the first character; its own first
        // character decides the boundary rather than the earlier partial.
        assertEquals(" 2day", AsrSegmentSpacing.forNext("Monday", "2day"))
        assertEquals("昨天", AsrSegmentSpacing.forNext("Monday", "昨天"))
    }
}
