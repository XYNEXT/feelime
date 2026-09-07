package com.feelime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkDownloadConsentTest {
    @Test
    fun `only a metered network needs confirmation`() {
        assertEquals(NetworkDownloadDecision.NO_NETWORK, NetworkDownloadConsent.decision(false, false))
        assertEquals(NetworkDownloadDecision.NO_NETWORK, NetworkDownloadConsent.decision(false, true))
        assertEquals(NetworkDownloadDecision.CONFIRM, NetworkDownloadConsent.decision(true, true))
        assertEquals(NetworkDownloadDecision.DOWNLOAD, NetworkDownloadConsent.decision(true, false))
    }

    @Test
    fun `unmetered request starts without a pending prompt`() {
        val gate = ModelDownloadConsentGate()
        val request = ModelDownloadRequest("m1", "model", 42)

        val result = gate.request(request, NetworkDownloadDecision.DOWNLOAD)

        assertEquals(ModelDownloadGateAction.START, result.action)
        assertEquals(request, result.request)
        assertEquals(ModelDownloadGateAction.STALE, gate.confirm("m1", true).action)
    }

    @Test
    fun `cancel consumes metered prompt and cannot start a download`() {
        val gate = ModelDownloadConsentGate()
        val request = ModelDownloadRequest("m1", "model", 42)
        assertEquals(ModelDownloadGateAction.ASK,
            gate.request(request, NetworkDownloadDecision.CONFIRM).action)

        val cancelled = gate.confirm("m1", false)
        assertEquals(ModelDownloadGateAction.REJECTED, cancelled.action)
        assertEquals(request, cancelled.request)
        assertEquals(ModelDownloadGateAction.STALE, gate.confirm("m1", true).action)
    }

    @Test
    fun `a newer prompt replaces the older model and approval is one shot`() {
        val gate = ModelDownloadConsentGate()
        val old = ModelDownloadRequest("old", "Old", 1)
        val newer = ModelDownloadRequest("new", "New", 2)
        gate.request(old, NetworkDownloadDecision.CONFIRM)
        gate.request(newer, NetworkDownloadDecision.CONFIRM)

        assertEquals(ModelDownloadGateAction.STALE, gate.confirm("old", true).action)
        assertEquals(ModelDownloadGateAction.START, gate.confirm("new", true).action)
        assertNull(gate.confirm("new", true).request)
    }
}
