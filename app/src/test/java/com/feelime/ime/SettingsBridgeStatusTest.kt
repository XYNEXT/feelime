package com.feelime.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBridgeStatusTest {
    private val packageName = "com.feelime.ime"
    private val service = "com.feelime.ime.FeelimeService"

    @Test
    fun acceptsBothAndroidComponentFlattenings() {
        assertTrue(matchesDefaultImeComponent("$packageName/.FeelimeService", packageName, service))
        assertTrue(matchesDefaultImeComponent("$packageName/$service", packageName, service))
        assertTrue(matchesDefaultImeComponent("$packageName/FeelimeService", packageName, service))
    }

    @Test
    fun rejectsAnotherPackageOrService() {
        assertFalse(matchesDefaultImeComponent("other/.FeelimeService", packageName, service))
        assertFalse(matchesDefaultImeComponent("$packageName/.OtherService", packageName, service))
        assertFalse(matchesDefaultImeComponent(null, packageName, service))
    }
}

