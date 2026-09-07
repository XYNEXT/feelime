package com.feelime.ime.panel

import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseInputCodeTest {
    private val initials = mapOf('你'.code to 'n', '好'.code to 'h', '世'.code to 's', '界'.code to 'j')
    private fun code(text: String) = PhraseInputCode.generate(text) { initials[it] }

    @Test fun chinesePhraseUsesInitials() {
        assertEquals("nhsj", code("你好，世界！"))
    }

    @Test fun latinPhraseUsesFirstThreeLetters() {
        assertEquals("hel", code("  Hello world"))
        assertEquals("été", code("été prochain"))
        assertEquals("при", code("Привет"))
    }

    @Test fun mixedTextKeepsLettersAndSkipsPunctuation() {
        assertEquals("nhabc", code("你好 ABC!"))
        assertEquals("hel", code("😀 Hello"))
        assertEquals("", code("😀！"))
    }

    @Test fun longChineseShortcutIsBounded() {
        assertEquals("nh".repeat(6), code("你好".repeat(10)))
    }
}
