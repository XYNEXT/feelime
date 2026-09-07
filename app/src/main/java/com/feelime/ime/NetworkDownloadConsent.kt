package com.feelime.ime

/** The only network policy the settings page needs to know about. */
enum class NetworkDownloadDecision {
    /** The active network is available and non-metered. */
    DOWNLOAD,
    /** The active network is metered; ask before starting a request. */
    CONFIRM,
    /** There is no usable active network. */
    NO_NETWORK,
}

/** Pure policy and one-shot pending-request gate for model downloads.
 *
 * Keeping the policy separate from ConnectivityManager makes the dangerous
 * edge cases testable on the JVM: a denied confirmation never reaches the
 * download starter, and every confirmation consumes the pending request. */
object NetworkDownloadConsent {
    fun decision(hasNetwork: Boolean, isMetered: Boolean): NetworkDownloadDecision =
        when {
            !hasNetwork -> NetworkDownloadDecision.NO_NETWORK
            isMetered -> NetworkDownloadDecision.CONFIRM
            else -> NetworkDownloadDecision.DOWNLOAD
        }
}

data class ModelDownloadRequest(
    val id: String,
    val name: String,
    val bytes: Long,
)

enum class ModelDownloadGateAction {
    START,
    ASK,
    NO_NETWORK,
    REJECTED,
    STALE,
}

data class ModelDownloadGateResult(
    val action: ModelDownloadGateAction,
    val request: ModelDownloadRequest? = null,
)

/** Serializes the single confirmation slot owned by SettingsBridge. */
class ModelDownloadConsentGate {
    private val lock = Any()
    private var pending: ModelDownloadRequest? = null

    /** A new request invalidates any older prompt, including one for the same
     * model. Only one model can be awaiting an explicit answer. */
    fun request(request: ModelDownloadRequest, decision: NetworkDownloadDecision): ModelDownloadGateResult =
        synchronized(lock) {
            pending = null
            when (decision) {
                NetworkDownloadDecision.DOWNLOAD -> ModelDownloadGateResult(ModelDownloadGateAction.START, request)
                NetworkDownloadDecision.CONFIRM -> {
                    pending = request
                    ModelDownloadGateResult(ModelDownloadGateAction.ASK, request)
                }
                NetworkDownloadDecision.NO_NETWORK -> ModelDownloadGateResult(ModelDownloadGateAction.NO_NETWORK)
            }
        }

    /** A matching answer consumes the slot before acting. A stale answer for
     * an older model leaves the newer prompt intact; reusing an old approval
     * therefore cannot start a second download or cancel the new prompt. */
    fun confirm(id: String, approved: Boolean): ModelDownloadGateResult = synchronized(lock) {
        val request = pending
        if (request == null || request.id != id) {
            ModelDownloadGateResult(ModelDownloadGateAction.STALE)
        } else {
            pending = null
            if (approved) {
                ModelDownloadGateResult(ModelDownloadGateAction.START, request)
            } else {
                ModelDownloadGateResult(ModelDownloadGateAction.REJECTED, request)
            }
        }
    }

    fun cancelPending() = synchronized(lock) { pending = null }
}
