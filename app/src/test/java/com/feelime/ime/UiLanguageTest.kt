package com.feelime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiLanguageTest {
    @Test
    fun `wire choices are stable and invalid values are rejected`() {
        assertEquals(listOf("auto", "zh", "en"), UiLanguage.choices)
        assertEquals("auto", UiLanguage.normalizeChoice(" AUTO "))
        assertEquals("zh", UiLanguage.normalizeChoice("ZH"))
        assertEquals("en", UiLanguage.normalizeChoice("en"))
        assertNull(UiLanguage.normalizeChoice("fr"))
        assertNull(UiLanguage.normalizeChoice(null))
    }

    @Test
    fun `auto follows system language while explicit choice wins`() {
        assertEquals("zh", UiLanguage.resolveLocale("auto", "zh-CN"))
        assertEquals("zh", UiLanguage.resolveLocale("auto", "zh-Hant"))
        assertEquals("en", UiLanguage.resolveLocale("auto", "en-US"))
        assertEquals("en", UiLanguage.resolveLocale("auto", "de-DE"))
        assertEquals("zh", UiLanguage.resolveLocale("zh", "en-US"))
        assertEquals("en", UiLanguage.resolveLocale("en", "zh-CN"))
        assertEquals("en", UiLanguage.resolveLocale("unknown", "fr-FR"))
    }
}
