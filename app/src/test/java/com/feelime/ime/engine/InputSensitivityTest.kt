package com.feelime.ime.engine

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sensitivity matrix for the clipboard/mic gates (see InputSensitivity doc). */
class InputSensitivityTest {
    @Test
    fun `password variations are sensitive`() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(InputSensitivity.isSensitive(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(InputSensitivity.isSensitive(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(InputSensitivity.isSensitive(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
    }

    @Test
    fun `number password is sensitive`() {
        val number = InputType.TYPE_CLASS_NUMBER
        assertTrue(InputSensitivity.isSensitive(number or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun `plain editors are not sensitive`() {
        val text = InputType.TYPE_CLASS_TEXT
        assertFalse(InputSensitivity.isSensitive(text))
        assertFalse(
            InputSensitivity.isSensitive(
                text or InputType.TYPE_TEXT_VARIATION_NORMAL or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            ),
        )
        assertFalse(InputSensitivity.isSensitive(InputType.TYPE_CLASS_NUMBER))
        assertFalse(InputSensitivity.isSensitive(InputType.TYPE_CLASS_DATETIME))
        assertFalse(InputSensitivity.isSensitive(null))
    }
}
