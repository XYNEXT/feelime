package com.feelime.ime

/**
 * Keeps cursor capability probes scoped to one InputConnection.
 *
 * A null snapshot is not a permanent capability result: Android can also
 * return null for a temporary timeout or an invalidated connection. While a
 * probe is cooling down, raw arrows avoid paying for the same slow RPC on
 * every scrub frame; once the cooldown expires, the connection is probed
 * again.
 */
internal class CursorSnapshotSupport {
    enum class Strategy {
        FULL_SNAPSHOT,
        TEXT_AROUND_CURSOR,
        NATIVE_ARROWS,
    }

    private var unsupportedOwner: Any? = null
    private var textFallbackOwner: Any? = null
    private var retryAtMs = 0L

    fun reset() {
        unsupportedOwner = null
        textFallbackOwner = null
        retryAtMs = 0L
    }

    fun markTextFallback(owner: Any) {
        unsupportedOwner = null
        textFallbackOwner = owner
        retryAtMs = 0L
    }

    fun markSupported(owner: Any) {
        if (textFallbackOwner === owner || unsupportedOwner === owner) {
            textFallbackOwner = null
            unsupportedOwner = null
            retryAtMs = 0L
        }
    }

    fun markUnsupported(owner: Any, nowMs: Long = 0L) {
        textFallbackOwner = null
        unsupportedOwner = owner
        retryAtMs = nowMs + RETRY_DELAY_MS
    }

    fun strategy(owner: Any, nowMs: Long = 0L): Strategy {
        if (textFallbackOwner === owner) return Strategy.TEXT_AROUND_CURSOR
        if (unsupportedOwner === owner && nowMs < retryAtMs) {
            return Strategy.NATIVE_ARROWS
        }
        return Strategy.FULL_SNAPSHOT
    }

    fun isUnsupported(owner: Any, nowMs: Long = 0L): Boolean =
        strategy(owner, nowMs) == Strategy.NATIVE_ARROWS

    companion object {
        /** Do not repeat two potentially slow snapshot calls on every frame. */
        const val RETRY_DELAY_MS = 2_000L
    }
}
