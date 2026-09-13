package com.feelime.ime.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capability gate: the three panel capabilities and hot-update compat. */
class BridgeContractCapabilityTest {
    private val legacy = listOf(
        "text-input-v1",
        "candidate-revision-v1",
        "voice-session-v1",
        "cursor-repeat-v1",
        "ime-control-v1",
        "keyboard-update-status-v1",
    )

    @Test
    fun `panel capabilities are declared`() {
        for (cap in listOf(
            "voice-cancel-v1",
            "clipboard-v1",
            "favorites-v2",
            "panel-compose-v1",
            "commit-text-v1",
            "compose-control-v1",
            "unicode-compose-v1",
            "cursor-delta-v1",
        )) {
            assertTrue(cap in BridgeContract.CAPABILITIES)
        }
    }

    @Test
    fun `unicode composition accepts letters and marks by code point`() {
        assertTrue(BridgeContract.isValidComposition("ête"))
        assertTrue(BridgeContract.isValidComposition("ё"))
        assertTrue(BridgeContract.isValidComposition("e\u0301"))
        // sogou double pinyin puts ing on ';' - variant replays carry it.
        assertTrue(BridgeContract.isValidComposition("x;'an"))
        assertFalse(BridgeContract.isValidComposition("e t"))
        assertFalse(BridgeContract.isValidComposition("e1"))
    }

    @Test
    fun `pre-v2 keyboard is rejected by new native`() {
        // FavoritesAdd/Update grew a code argument. A keyboard
        // built before favorites-v2 would call with the OLD argument order
        // (text, token) and misroute the token - the handshake must refuse
        // it instead of letting the bridge garble the call.
        assertFalse(BridgeContract.isCompatible(1, legacy + listOf("favorites-v1")))
    }

    @Test
    fun `panel keyboard is rejected by legacy native`() {
        // Simulate the old native contract: the three new caps are unknown.
        val oldCaps = legacy.toSet()
        val panelCaps = legacy + listOf("clipboard-v1", "favorites-v2", "commit-text-v1")
        assertFalse(panelCaps.all(oldCaps::contains))
        // On the CURRENT native both keyboards' caps are a subset, so both
        // would pass the gate - the v2 split above is what guards mixing.
        assertTrue(BridgeContract.isCompatible(1, panelCaps))
    }

    @Test
    fun `api version bounds still hold`() {
        val caps = BridgeContract.CAPABILITIES
        assertFalse(BridgeContract.isCompatible(0L, caps))
        assertFalse(BridgeContract.isCompatible(BridgeContract.NATIVE_API_VERSION + 1L, caps))
        assertTrue(BridgeContract.isCompatible(BridgeContract.NATIVE_API_VERSION.toLong(), caps))
    }

    @Test
    fun `t9 composition replays allow digits two through nine only`() {
        // T9 音节条重写：已选音节字母 + 剩余数字（t9.md §3）。
        assertTrue(BridgeContract.isValidComposition("ni426", allowDigits = true))
        assertTrue(BridgeContract.isValidComposition("64426", allowDigits = true))
        // 1/0 不在 T9 alphabet（死输入），标点/空白照拒。
        assertFalse(BridgeContract.isValidComposition("ni100", allowDigits = true))
        assertFalse(BridgeContract.isValidComposition("ni 426", allowDigits = true))
        assertFalse(BridgeContract.isValidComposition("ni,426", allowDigits = true))
        // 非 T9 通道维持原语义：数字一律拒绝。
        assertFalse(BridgeContract.isValidComposition("ni426"))
    }
}
