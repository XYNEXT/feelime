package com.feelime.ime.engine

import android.text.InputType

/**
 * Editor-sensitivity policy shared by every gated surface: clipboard history
 * collection/rendering (FeelimeService/ClipboardStore) and the mic button.
 *
 * Extracted from FeelimeService so the decision matrix has a direct JVM oracle;
 * on ColorOS builds the system security keyboard claims real password editors,
 * so a device-level DOM assertion can never exercise this path (the device
 * suites degrade to checking that claim instead - see verification-plan.md).
 */
object InputSensitivity {
    fun isSensitive(inputType: Int?): Boolean {
        if (inputType == null) return false
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true
                else -> false
            }
            InputType.TYPE_CLASS_NUMBER ->
                inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
