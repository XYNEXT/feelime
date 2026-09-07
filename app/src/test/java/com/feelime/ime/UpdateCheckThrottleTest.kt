package com.feelime.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckThrottleTest {
    @Test
    fun `first enabled check uses the default source`() {
        assertTrue(UpdateCheckThrottle.isDue(
            enabled = true,
            sourceConfigured = false,
            source = "",
            nowMs = 1_000L,
            lastAttemptMs = 0L,
        ))
    }

    @Test
    fun `cleared source disables automatic checks`() {
        assertFalse(UpdateCheckThrottle.isDue(
            enabled = true,
            sourceConfigured = true,
            source = "",
            nowMs = 100_000L,
            lastAttemptMs = 0L,
        ))
    }

    @Test
    fun `failed attempt still blocks retries for one day`() {
        val now = 10_000_000L
        assertFalse(UpdateCheckThrottle.isDue(
            enabled = true,
            sourceConfigured = true,
            source = "https://example.com/metainfo.json",
            nowMs = now,
            lastAttemptMs = now - UpdateCheckThrottle.INTERVAL_MS + 1L,
        ))
        assertTrue(UpdateCheckThrottle.isDue(
            enabled = true,
            sourceConfigured = true,
            source = "https://example.com/metainfo.json",
            nowMs = now,
            lastAttemptMs = now - UpdateCheckThrottle.INTERVAL_MS,
        ))
    }

    @Test
    fun `disabled setting and clock rollback never trigger`() {
        assertFalse(UpdateCheckThrottle.isDue(
            enabled = false,
            sourceConfigured = false,
            source = "",
            nowMs = 1_000L,
            lastAttemptMs = 0L,
        ))
        assertFalse(UpdateCheckThrottle.isDue(
            enabled = true,
            sourceConfigured = true,
            source = "https://example.com/metainfo.json",
            nowMs = 1_000L,
            lastAttemptMs = 2_000L,
        ))
    }
}
