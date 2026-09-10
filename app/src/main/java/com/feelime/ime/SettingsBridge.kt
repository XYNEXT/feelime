package com.feelime.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import android.webkit.JavascriptInterface
import com.feelime.ime.backup.AndroidPrefs
import com.feelime.ime.backup.UserdataBackup
import com.feelime.ime.update.GithubReleaseSource
import com.feelime.ime.update.KeyboardPackageVerifier
import com.feelime.ime.update.KeyboardSource
import com.feelime.ime.update.KeyboardPackageReader
import com.feelime.ime.update.KeyboardStore
import com.feelime.ime.update.KeyboardUpdateCenter
import com.feelime.ime.update.KeyboardUpdateDownloader
import com.feelime.ime.update.KeyboardUpdateErrorCode
import com.feelime.ime.update.HttpUrlConnectionFactory
import com.feelime.ime.update.UpdateMetainfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.Executors

/** userdata 恢复广播：设置页发，IME 收（userdb 暂存已就位，
 * 换目录+重建引擎会话，docs/design/userdata.md §1.2）。 */
const val ACTION_USERDATA_RESTORED = "com.feelime.ime.USERDATA_RESTORED"

/** 双拼方案切换广播：设置页发，IME 收（当前是双拼会话时按新 schema
 * 重建会话，并重推 hello 让键盘换解析表/sep 键，double-pinyin.md §2）。 */
const val ACTION_DP_SCHEME_CHANGED = "com.feelime.ime.DP_SCHEME_CHANGED"

/** The full-settings WebView bridge (design §6.2).
 *
 * Same handshake as the IME bridge (design §5.3): the activity mints a
 * per-page-load token, pushes it through onBridgeHello, and every call must
 * carry it back ([guarded]). State changes travel as ONE full-state push
 * (window.FeelimeSettings.onEvent({type:"state",...})) - the page is small
 * enough that diffing is not worth the bug surface.
 *
 * Custom keyboard rows (design §15): the settings page edits the same table the
 * IME uses. The store moved to native prefs ([CustomKeysStore]) - the IME
 * WebView mirrors it through its own bridge (FeelimeService.ImeBridge), so
 * both WebViews share one source of truth like clipboard/favorites.
 *
 * The clipboard/favorites management moved out of this page
 * (keyboard panel only), so their stores/entries live solely in
 * FeelimeService.ImeBridge now. */
class SettingsBridge(
    private val context: Context,
    private val host: Host,
) {
    interface Host {
        /** Push a JS event; implementations must marshal to the UI thread
         * and call through to WebView.evaluateJavascript. */
        fun evaluate(script: String)
        fun requestMicPermission()
        fun showImeEnableSettings()
        fun showImePicker()
        /** Launch ACTION_OPEN_DOCUMENT for a verified model archive. */
        fun openModelDocument(modelId: String)
        /** Launch ACTION_OPEN_DOCUMENT for a local keyboard ZIP package. */
        fun openKeyboardDocument() = Unit
        /** Launch ACTION_CREATE_DOCUMENT for the userdata backup (userdata.md §1). */
        fun createBackupDocument() = Unit
        /** Launch ACTION_OPEN_DOCUMENT for a userdata backup file. */
        fun openBackupDocument() = Unit
        fun addImeShortcut() = Unit
        fun addImeTile() = Unit
        fun onSettingsChanged()
    }

    @Volatile var pageToken: String = ""

    /** The page reports whether a sub-page is open so the
     * shell's BACK callback returns home first instead of finishing
     * (design §6.2). */
    @Volatile var onSubPage: Boolean = false

    private val modelStore = ModelStore(context)
    private val customKeysStore = CustomKeysStore(context)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "feelime-settings") }
    private val uiPreferences = UiLanguage.preferences(context)
    private val modelDownloadGate = ModelDownloadConsentGate()

    /** 签名不符但用户可能要装的包（§3 pending/confirm 状态机，
     * 仿 modelDownloadGate；只活在本进程内存，页面刷新即弃）。
     * bytes/id/sha256= 钉扎三者一体发布、一体取走：bridge binder 线程与
     * 安装 worker 并发，拆散字段会让确认绑错包或丢钉扎。 */
    private class PendingKeyboardInstall(
        val bytes: ByteArray,
        val id: String,
        val pin: String?,
    )

    private val pendingLock = Any()
    private var pendingKeyboardInstall: PendingKeyboardInstall? = null
    private val lifecycleLock = Any()
    @Volatile private var closed = false
    private var modelDownloadNetwork: ModelDownloadNetwork? = null

    /**
     * The settings page can stay open while another surface changes the
     * language preference.  Re-push both the shell hello (theme/language) and
     * the full state so the current page redraws immediately.
     */
    private val uiLanguageListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == UiLanguage.KEY_CHOICE && !closed) {
            host.onSettingsChanged()
            pushState()
        }
    }

    init {
        uiPreferences.registerOnSharedPreferenceChangeListener(uiLanguageListener)
    }

    /** The store fires a callback per 64 KB chunk - forward only
     * whole-percent steps (and the terminal states) so the WebView gets one
     * evaluate per percent instead of hundreds per second. */
    private val lastPercent = java.util.concurrent.atomic.AtomicInteger(-1)

    private val modelListener = object : ModelStore.Listener {
        override fun onProgress(modelId: String, doneBytes: Long, totalBytes: Long) {
            if (closed) return
            val percent = if (totalBytes > 0) ((doneBytes * 100) / totalBytes).toInt() else 0
            if (lastPercent.getAndSet(percent) == percent) return
            pushEvent(
                JSONObject()
                    .put("type", "modelProgress")
                    .put("id", modelId)
                    .put("percent", percent)
                    .put("doneBytes", doneBytes)
                    .put("totalBytes", totalBytes),
            )
        }

        override fun onFinished(modelId: String, state: ModelStore.State, error: String?) {
            if (closed) return
            lastPercent.set(-1)
            if (!error.isNullOrBlank()) {
                val saved = modelStore.lastDownloadError(modelId)
                pushModelError(
                    modelId,
                    saved?.code ?: "MODEL_DOWNLOAD_FAILED",
                    saved?.detail ?: error,
                )
            }
            pushState()
        }

        override fun onStatus(modelId: String, status: String) {
            if (closed) return
            pushEvent(
                JSONObject()
                    .put("type", "modelImportStatus")
                    .put("id", modelId)
                    .put("status", status),
            )
        }
    }

    fun release() = synchronized(lifecycleLock) {
        if (closed) return
        closed = true
        uiPreferences.unregisterOnSharedPreferenceChangeListener(uiLanguageListener)
        modelDownloadGate.cancelPending()
        invalidatePendingInstall()
        modelDownloadNetwork?.close()
        modelDownloadNetwork = null
        modelStore.release()
        worker.shutdown()
    }

    // ---- push -----------------------------------------------------------

    /** Passthrough for the shell (hello push lands after onPageFinished). */
    fun evaluate(script: String) {
        if (closed) return
        runCatching { host.evaluate(script) }
            .onFailure { Log.w(TAG, "settings evaluate failed", it) }
    }

    /** Full state snapshot; also the ready-time payload. */
    fun pushState() {
        // A guarded handler can still run after release() (bridge
        // callback racing onDestroy) - a rejected execute must not crash.
        runCatching { worker.execute { pushStateSync() } }
            .onFailure { Log.w(TAG, "settings state push after release", it) }
    }

    private fun pushStateSync() {
        if (closed) return
        val payload = runCatching { stateJson() }
            .onFailure { Log.w(TAG, "settings state build failed", it) }
            .getOrNull() ?: return
        // release() may race the state build.  Do not enqueue a host callback
        // after the bridge has been closed; the Activity also guards its UI
        // runnable for the remaining check-to-post race.
        if (closed) return
        runCatching { host.evaluate("window.FeelimeSettings && window.FeelimeSettings.onEvent($payload)") }
            .onFailure { Log.w(TAG, "settings state push failed", it) }
    }

    private fun pushEvent(payload: JSONObject) {
        if (closed) return
        runCatching { host.evaluate("window.FeelimeSettings && window.FeelimeSettings.onEvent($payload)") }
            .onFailure { Log.w(TAG, "settings event push failed", it) }
    }

    fun stateJson(): String {
        val prefs = context.getSharedPreferences("keyboard_update", Context.MODE_PRIVATE)
        val sourcePreferenceSet = prefs.contains(KeyboardStore.STATE_SOURCE_URL)
        val savedSource = prefs.getString(KeyboardStore.STATE_SOURCE_URL, "") ?: ""
        val sourceMode = when {
            !sourcePreferenceSet -> "default"
            savedSource.isBlank() -> "disabled"
            else -> "custom"
        }
        val source = if (sourcePreferenceSet) savedSource else DEFAULT_GITHUB_SOURCE
        val active = KeyboardUpdateCenter.activeKeyboard(context)
        val state = JSONObject()
            .put("theme", themeName())
            .put("channel", if (BuildConfig.PLAY_DISTRIBUTION) "play" else "direct")
            // Keep the distribution capability explicit for settings UI
            // actions.  The channel string remains for older pages, while
            // new pages can show the app-update entry without inferring it.
            .put("playDistribution", BuildConfig.PLAY_DISTRIBUTION)
            .put("modelBackend", modelStore.modelBackend().value)
            .put("modelDownloadSource", JSONObject().apply {
                val source = modelStore.modelDownloadSource()
                put("mode", source.source.value)
                put("customBase", source.customBase)
                put("customArchiveUrl", source.customArchiveUrl)
            })
            // Stable protocol values; the page resolves labels from these
            // codes and never infers UI language from the input mode.
            .put("uiLanguage", UiLanguage.choice(context))
            .put("uiLocale", UiLanguage.locale(context))
            .put("ime", JSONObject()
                .put("enabled", hostEnabled())
                .put("isDefault", hostIsDefaultIme()))
            .put("mic", JSONObject().put("granted", micGranted()))
            .put("dpScheme", com.feelime.ime.engine.DoublePinyinScheme.resolve(context))
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("keyboardVersion", keyboardVersion())
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("release", Build.VERSION.RELEASE)
                .put("sdkInt", Build.VERSION.SDK_INT))
            .put("models", modelsJson())
            .put("asr", JSONObject()
                .put("stripPeriod", asrPrefs().getBoolean(AsrSettings.KEY_STRIP_FINAL_PERIOD, true))
                .put("hotwords", asrPrefs().getString(AsrSettings.KEY_HOTWORDS, "") ?: ""))
            .put("custom", JSONObject()
                .put("enabled", customKeysStore.enabled())
                .put("summary", customKeysStore.summary(context))
                .put("json", customKeysStore.json()))
            .put("update", JSONObject()
                // Keep the absence of a preference distinct from an explicit
                // empty value: a fresh install shows the official source,
                // while clearing it disables automatic checks.
                .put("source", source)
                .put("sourceMode", sourceMode)
                .put("sourceConfigured", sourcePreferenceSet)
                .put("autoCheck", prefs.getBoolean(STATE_AUTO_CHECK_ENABLED, false))
                .put("autoCheckLastAttemptAt", prefs.getLong(STATE_AUTO_CHECK_LAST_ATTEMPT_AT, 0L))
                .put("url", prefs.getString(KeyboardStore.STATE_UPDATE_URL, "") ?: "")
                .put("state", prefs.getString(KeyboardStore.STATE_UPDATE_STATE, "BUILT_IN") ?: "BUILT_IN")
                .put("lastErrorCode", prefs.getString(KeyboardStore.STATE_LAST_ERROR_CODE, "") ?: "")
                .put("lastErrorMessage", updateErrorMessage(
                    prefs.getString(KeyboardStore.STATE_LAST_ERROR_CODE, "") ?: "",
                    "",
                ))
                .put("lastErrorDetail", prefs.getString(KeyboardStore.STATE_LAST_ERROR_MESSAGE, "") ?: "")
                // Keep the old combined field for older settings pages while
                // exposing code and message separately to new pages.
                .put("lastError", (prefs.getString(KeyboardStore.STATE_LAST_ERROR_CODE, null) ?: "") +
                    " " + (prefs.getString(KeyboardStore.STATE_LAST_ERROR_MESSAGE, null) ?: ""))
                .put("lastSuccessAt", prefs.getString(KeyboardStore.STATE_LAST_SUCCESS_AT, "") ?: "")
                .put("activeSource", if (active.source == KeyboardSource.BUILT_IN) "built_in" else "hot")
                .put("activeVersion", active.version)
                .put("activeContentHash", active.contentHash ?: "")
                .put("activeSigned", active.signed)
                .put("activeSignatureConfirmed", active.signatureConfirmed))
            .put("notices", notices())
        return JSONObject().put("type", "state").put("state", state).toString()
    }

    private fun modelsJson(): JSONArray = JSONArray().apply {
        for (model in modelStore.manifest().models) {
            val state = modelStore.state(model)
            val error = modelStore.lastDownloadError(model.id)
            put(JSONObject()
                .put("id", model.id)
                .put("title", modelTitle(model))
                .put("sizeBytes", model.totalBytes)
                .put("downloadBytes", model.totalDownloadBytes)
                .put("errorCode", error?.code ?: "")
                .put("errorDetail", error?.detail ?: "")
                .put("state", when (state) {
                    ModelStore.State.BUILT_IN -> "built_in"
                    ModelStore.State.MISSING -> "missing"
                    ModelStore.State.DOWNLOADING -> "downloading"
                    ModelStore.State.IMPORTING -> "importing"
                    ModelStore.State.INSTALLED -> "installed"
                    ModelStore.State.BROKEN -> "broken"
                }))
        }
    }

    private fun modelTitle(model: ModelSpec): String = when (model.id) {
        "streaming-zipformer-bilingual-zh-en" -> t(context, "流式语音识别（中英）", "Streaming speech recognition (Chinese/English)")
        "paraformer-zh-small" -> t(context, "整句纠错识别", "Full-sentence correction")
        "offline-punct-zh-en",
        "online-punct-en" -> t(context, "中英标点恢复", "Chinese-English punctuation restoration") // Keep old downloaded manifests readable during migration.
        else -> model.title
    }

    private fun notices(): String = runCatching {
        context.assets.open("third-party/THIRD_PARTY_NOTICES.txt")
            .use { stream -> stream.readBytes().decodeToString() }
    }.getOrDefault(t(context, "第三方说明文件缺失", "Third-party notices are missing"))

    private fun updateErrorMessage(code: String, detail: String): String {
        if (code.isBlank() && detail.isBlank()) return ""
        val label = when (code) {
            "INVALID_SOURCE" -> t(context, "更新源地址无效", "The update source URL is invalid")
            "NO_RELEASE" -> t(context, "尚无可用发布", "No release is currently available")
            "NO_INSTALLABLE_ASSET" -> t(context, "发布中没有可安装的键盘包", "The release has no installable keyboard package")
            "AMBIGUOUS_ASSET" -> t(context, "发布中的键盘包不唯一", "The release has ambiguous keyboard packages")
            "INVALID_ASSET_URL" -> t(context, "发布资产下载地址无效", "The release asset URL is invalid")
            "RATE_LIMITED" -> t(context, "GitHub 请求受到限流", "GitHub rate limited the request")
            "NETWORK_ERROR" -> t(context, "网络请求失败", "The network request failed")
            "BAD_RESPONSE" -> t(context, "更新源响应无效", "The update source returned an invalid response")
            "INTERNAL_ERROR" -> t(context, "更新处理失败", "The update operation failed")
            "MISSING_SOURCE_URL" -> t(context, "缺少更新源地址", "The update source URL is missing")
            "MISSING_PACKAGE_URL" -> t(context, "缺少键盘包地址", "The keyboard package URL is missing")
            else -> t(context, "键盘更新失败", "Keyboard update failed")
        }
        return label
    }

    private fun themeName(): String {
        val night = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    }

    private fun hostEnabled(): Boolean = runCatching {
        inputMethodManager().enabledInputMethodList.any { it.packageName == context.packageName }
    }.getOrDefault(false)

    private fun hostIsDefaultIme(): Boolean = runCatching {
        val flat = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
        )
        matchesDefaultImeComponent(
            flat,
            context.packageName,
            FeelimeService::class.java.name,
        )
    }.getOrDefault(false)

    private fun micGranted(): Boolean = runCatching {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun inputMethodManager() =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager

    private fun asrPrefs() = context.getSharedPreferences(AsrSettings.PREFS, Context.MODE_PRIVATE)

    private fun networkDecision(): NetworkDownloadDecision {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkDownloadDecision.NO_NETWORK
        val network = manager.activeNetwork ?: return NetworkDownloadDecision.NO_NETWORK
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return NetworkDownloadDecision.NO_NETWORK
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return NetworkDownloadConsent.decision(hasInternet, manager.isActiveNetworkMetered)
    }

    private fun startModelDownload(model: ModelSpec, allowMetered: Boolean = false) = synchronized(lifecycleLock) {
        if (closed || modelDownloadNetwork != null) return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork
        if (manager == null || network == null) {
            pushModelError(model.id, "NO_NETWORK")
            return
        }
        val session = ModelDownloadNetwork(manager, network, allowMetered)
        try {
            session.check()
        } catch (_: ModelDownloadNetworkChanged) {
            session.close()
            pushModelError(model.id, "MODEL_NETWORK_CHANGED")
            return
        }
        val listener = object : ModelStore.Listener {
            override fun onProgress(modelId: String, doneBytes: Long, totalBytes: Long) =
                modelListener.onProgress(modelId, doneBytes, totalBytes)

            override fun onFinished(modelId: String, state: ModelStore.State, error: String?) {
                val changed = runCatching { session.check() }.isFailure
                session.close()
                synchronized(lifecycleLock) {
                    if (modelDownloadNetwork === session) modelDownloadNetwork = null
                    if (closed) return
                }
                if (session.cancelledByUser) {
                    modelListener.onFinished(modelId, state, null)
                } else if (changed && state != ModelStore.State.INSTALLED) {
                    lastPercent.set(-1)
                    pushModelError(modelId, "MODEL_NETWORK_CHANGED")
                    pushState()
                } else {
                    modelListener.onFinished(modelId, state, error)
                }
            }
        }
        modelDownloadNetwork = session
        var accepted = false
        try {
            accepted = modelStore.download(model, listener, session.transport, session::check)
            if (accepted) pushState()
        } finally {
            if (!accepted) {
                modelDownloadNetwork = null
                session.close()
            }
        }
    }

    private fun startModelImport(model: ModelSpec, uri: Uri) = synchronized(lifecycleLock) {
        if (closed) return
        val accepted = modelStore.importModel(model, context.contentResolver, uri, modelListener)
        if (accepted) {
            pushState()
        } else {
            pushModelError(model.id, "MODEL_IMPORT_BUSY")
        }
    }

    private fun modelRequest(model: ModelSpec): ModelDownloadRequest = ModelDownloadRequest(
        id = model.id,
        name = modelTitle(model),
        bytes = model.totalDownloadBytes,
    )

    private fun pushModelError(id: String, code: String) {
        pushModelError(id, code, "")
    }

    private fun pushModelError(id: String, code: String, detail: String) {
        val message = when (code) {
            "NO_NETWORK" -> t(context, "当前没有可用的网络连接", "No network connection is available")
            "MODEL_NETWORK_CHANGED" -> t(context, "网络已变化，下载已暂停。请重新点击下载确认流量后继续", "The network changed and the download paused. Tap Download to confirm and resume")
            "STALE_CONFIRMATION" -> t(context, "下载确认已过期，请重新点击下载", "The download confirmation expired; tap Download again")
            "MODEL_IMPORT_BUSY" -> t(context, "模型正在处理，请稍候", "The model is already being processed")
            "MODEL_IMPORT_FAILED" -> t(context, "模型导入失败，请检查归档格式和清单后重试", "Model import failed; check the archive format and manifest, then try again")
            else -> t(context, "模型下载失败，请稍后重试", "Model download failed; try again later")
        }
        if (id.isNotBlank() && code != "MODEL_IMPORT_BUSY") {
            modelStore.recordDownloadError(id, code, detail)
        }
        pushEvent(
            JSONObject()
                .put("type", "modelDownloadError")
                .put("id", id)
                .put("code", code)
                .put("message", message)
                .put("detail", detail.take(240)),
        )
    }

    private fun keyboardVersion(): String = runCatching {
        context.assets.open("keyboard/VERSION").use { it.readBytes().decodeToString().trim() }
    }.getOrDefault("")

    // ---- JS entry points -------------------------------------------------

    /** Page readiness ping → pushes the first full state. */
    @JavascriptInterface
    fun ready(token: String) = guarded(token) {
        pushState()
        maybeAutoCheck()
    }

    @JavascriptInterface
    fun enableIme(token: String) = guarded(token) { host.showImeEnableSettings() }

    @JavascriptInterface
    fun pickIme(token: String) = guarded(token) { host.showImePicker() }

    @JavascriptInterface
    fun requestMic(token: String) = guarded(token) { host.requestMicPermission() }

    /** Open the native application listing.  Play builds use this for APK
     * updates; keyboard ZIP updates continue through the signed bridge flow.
     * Direct builds deliberately ignore the call so a stale page cannot route
     * them to a store listing.
     */
    @JavascriptInterface
    fun openAppStore(token: String) = guarded(token) {
        if (BuildConfig.PLAY_DISTRIBUTION) openPlayListing()
    }

    @JavascriptInterface
    fun setModelBackend(value: String, token: String) = guarded(token) {
        val backend = ModelBackend.fromValue(value)
        if (backend == null) {
            pushState()
            return@guarded
        }
        modelStore.setModelBackend(backend)
        pushState()
    }

    @JavascriptInterface
    fun setModelDownloadSource(
        value: String,
        customBase: String,
        customArchiveUrl: String,
        token: String,
    ) = guarded(token) {
        val source = ModelDownloadSource.fromValue(value)
        if (!modelStore.setModelDownloadSource(source, customBase, customArchiveUrl)) {
            pushEvent(
                JSONObject()
                    .put("type", "modelSourceError")
                    .put("code", "INVALID_MODEL_SOURCE")
                    .put("message", t(context, "自定义源需要 HTTPS 仓库地址和对应的 tar.bz2 归档地址", "A custom source needs an HTTPS repository URL and its matching tar.bz2 archive URL")),
            )
            return@guarded
        }
        pushState()
    }

    /** 双拼方案切换（docs/design/double-pinyin.md §2）：落盘偏好并广播，
     * IME 在当前双拼会话上换 schema 重建；hello 会把新方案推给键盘。 */
    @JavascriptInterface
    fun setDoublePinyinScheme(value: String, token: String) = guarded(token) {
        if (!com.feelime.ime.engine.DoublePinyinScheme.set(context, value)) {
            pushEvent(
                JSONObject()
                    .put("type", "dpSchemeError")
                    .put("code", "BAD_DP_SCHEME")
                    .put("message", t(context, "双拼方案选项无效", "Invalid double-pinyin scheme")),
            )
            pushState()
            return@guarded
        }
        context.sendBroadcast(
            Intent(ACTION_DP_SCHEME_CHANGED).setPackage(context.packageName),
        )
        pushState()
    }

    /** Persist the UI language choice independently of the input mode. */
    @JavascriptInterface
    fun setUiLanguage(choice: String, token: String) = guarded(token) {
        if (!UiLanguage.setChoice(context, choice)) {
            pushEvent(
                JSONObject()
                    .put("type", "uiLanguageError")
                    .put("code", "BAD_UI_LANGUAGE")
                    .put("message", t(context, "界面语言选项无效", "Invalid interface language choice")),
            )
            return@guarded
        }
        // The preference listener also refreshes an already-open page (and
        // notifies the IME service); this direct push guarantees a response
        // even when Android dispatches the listener on a later main-loop turn.
        pushState()
        host.onSettingsChanged()
    }

    @JavascriptInterface
    fun setAutoUpdateCheck(enabled: Boolean, token: String) = guarded(token) {
        context.getSharedPreferences("keyboard_update", Context.MODE_PRIVATE)
            .edit().putBoolean(STATE_AUTO_CHECK_ENABLED, enabled).apply()
        pushState()
        if (enabled) maybeAutoCheck()
    }

    @JavascriptInterface
    fun downloadModel(id: String, token: String) = guarded(token) {
        val model = modelStore.manifest().models.firstOrNull { it.id == id } ?: return@guarded
        val result = modelDownloadGate.request(modelRequest(model), networkDecision())
        when (result.action) {
            ModelDownloadGateAction.START -> startModelDownload(model)
            ModelDownloadGateAction.ASK -> {
                val request = result.request ?: return@guarded
                pushEvent(
                    JSONObject()
                        .put("type", "modelDownloadConfirmation")
                        .put("id", request.id)
                        .put("name", request.name)
                        .put("bytes", request.bytes),
                )
            }
            ModelDownloadGateAction.NO_NETWORK -> pushModelError(id, "NO_NETWORK")
            ModelDownloadGateAction.REJECTED,
            ModelDownloadGateAction.STALE -> pushModelError(id, "STALE_CONFIRMATION")
        }
    }

    @JavascriptInterface
    fun openModelDocument(id: String, token: String) = guarded(token) {
        if (modelStore.manifest().models.any { it.id == id }) host.openModelDocument(id)
        else pushModelError(id, "MODEL_IMPORT_FAILED", "未知模型")
    }

    /** Select a local ZIP through Android's document provider. This path never
     * constructs a URL or enters the network downloader. */
    @JavascriptInterface
    fun openKeyboardDocument(token: String) = guarded(token) {
        host.openKeyboardDocument()
    }

    @JavascriptInterface
    fun addImeShortcut(token: String) = guarded(token) { host.addImeShortcut() }

    @JavascriptInterface
    fun addImeTile(token: String) = guarded(token) { host.addImeTile() }

    @JavascriptInterface

    /** Called by SetupActivity after the local keyboard ZIP picker returns. */
    fun installKeyboardFromUri(uri: Uri) {
        if (closed) return
        runCatching {
            worker.execute {
                if (closed) return@execute
                // 接受新导入请求即作废旧待确认包（§3）：读取/安装期间旧确认失效。
                invalidatePendingInstall()
                runCatching {
                    val read = runCatching {
                        context.contentResolver.openInputStream(uri)?.let(KeyboardPackageReader::read)
                            ?: throw java.io.IOException("selected document has no readable stream")
                    }
                    when {
                        read.isFailure -> {
                            failUpdate("IO_ERROR", read.exceptionOrNull()?.message ?: "local package")
                        }
                        read.getOrThrow() is KeyboardPackageReader.Result.TooLarge -> {
                            failUpdate("ZIP_TOO_LARGE", "${KeyboardPackageReader.MAX_BYTES} bytes")
                        }
                        else -> {
                            val bytes = (read.getOrThrow() as KeyboardPackageReader.Result.Ok).bytes
                            installWithConsent(bytes)
                        }
                    }
                    pushState()
                }.onFailure { failure ->
                    // Match the network install path: a write/activation or
                    // broadcast failure must leave a durable error and a
                    // visible state update instead of disappearing in the worker.
                    failUpdate("INTERNAL_ERROR", failure.javaClass.simpleName)
                }
            }
        }.onFailure { failure ->
            failUpdate("IO_ERROR", failure.message ?: "local package")
        }
    }

    // ---- userdata backup (docs/design/userdata.md §1) ---------------------

    @JavascriptInterface
    fun exportUserdata(token: String) = guarded(token) { host.createBackupDocument() }

    @JavascriptInterface
    fun openBackupDocument(token: String) = guarded(token) { host.openBackupDocument() }

    /** 设置页的 CreateDocument 回调：组包后写入选定位置。 */
    fun writeUserdataBackupToUri(uri: Uri) {
        if (closed) return
        runCatching {
            worker.execute {
                if (closed) return@execute
                runCatching {
                    val json = UserdataBackup(AndroidPrefs(context), context.filesDir, appVersion())
                        .export()
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(json.toString().toByteArray(Charsets.UTF_8))
                        output.flush()
                    } ?: throw java.io.IOException("selected document has no writable stream")
                    pushEvent(JSONObject().put("type", "backupStatus").put("direction", "export").put("ok", true))
                }.onFailure { failure ->
                    Log.w(TAG, "userdata export failed", failure)
                    pushEvent(
                        JSONObject().put("type", "backupStatus").put("direction", "export")
                            .put("ok", false).put("code", "IO_ERROR"),
                    )
                }
            }
        }
    }

    /** 设置页的 OpenDocument 回调：校验 kind/version 后恢复。settings 立即
     * 生效；userdb 暂存并广播给 IME，由它在关会话→换目录→开新会话的
     * 中间点换入（TextInputCoordinator.recreateEngineSession）。 */
    fun restoreUserdataBackupFromUri(uri: Uri) {
        if (closed) return
        runCatching {
            worker.execute {
                if (closed) return@execute
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        // 有界读取：超出上限即拒绝，不把整个文件吃进内存。
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(chunk)
                            if (read < 0) break
                            total += read
                            if (total > MAX_BACKUP_BYTES) {
                                throw java.io.IOException("backup too large")
                            }
                            buffer.write(chunk, 0, read)
                        }
                        buffer.toByteArray()
                    } ?: throw java.io.IOException("selected document has no readable stream")
                    val result = UserdataBackup(AndroidPrefs(context), context.filesDir).restore(bytes)
                    if (result is UserdataBackup.RestoreResult.Fail) {
                        pushEvent(
                            JSONObject().put("type", "backupStatus").put("direction", "import")
                                .put("ok", false).put("code", result.code),
                        )
                    } else {
                        com.feelime.ime.panel.PanelStoreSignals.fireFavoritesChanged()
                        // 不带词库的备份也要广播：键盘页要刷新运行时设置；
                        // 页面不在时 rev 协议保证下次握手仍会拉到恢复值。
                        context.sendBroadcast(
                            Intent(ACTION_USERDATA_RESTORED).setPackage(context.packageName),
                        )
                        pushEvent(
                            JSONObject().put("type", "backupStatus").put("direction", "import").put("ok", true),
                        )
                    }
                    pushState()
                }.onFailure { failure ->
                    Log.w(TAG, "userdata import failed", failure)
                    pushEvent(
                        JSONObject().put("type", "backupStatus").put("direction", "import")
                            .put("ok", false).put("code", "IO_ERROR"),
                    )
                }
            }
        }
    }

    /** 从哪版 App 导出（信息性，恢复不依赖）。 */
    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** 签名不符的确认导入（设计 §3）：凭 id 只认最近一次暂存的
     * SIGNATURE_BAD 包（本地导入 / URL 下载同路）；一次确认装一份，
     * 装完/取消即弃。URL 路径的 sha256= 钉扎随包重放。 */
    @JavascriptInterface
    fun confirmKeyboardInstall(id: String, token: String) = guarded(token) {
        val pending = takePendingInstall(id)
        if (pending == null) {
            pushUpdateError("NO_PENDING_PACKAGE", "")
            return@guarded
        }
        worker.execute {
            if (closed) return@execute
            runCatching {
                val result = KeyboardUpdateCenter.store(context).install(
                    pending.bytes, fragmentPin = pending.pin, confirmBadSignature = true)
                if (result is KeyboardStore.InstallResult.Ok) {
                    KeyboardUpdateCenter.notifyUpdated(context)
                } else if (result is KeyboardStore.InstallResult.Fail) {
                    pushUpdateError(result.code.name, result.detail)
                }
                pushState()
            }.onFailure { failure ->
                failUpdate("INTERNAL_ERROR", failure.javaClass.simpleName)
            }
        }
    }

    /** 用户取消签名确认：作废待确认包，避免旧包残留到下一次操作；
     * 带着旧 id 的迟到取消不动更新的请求。 */
    @JavascriptInterface
    fun dismissKeyboardInstall(id: String, token: String) = guarded(token) {
        synchronized(pendingLock) {
            val pending = pendingKeyboardInstall
            if (id.isEmpty() || pending == null || id == pending.id) {
                pendingKeyboardInstall = null
            }
        }
    }

    /** 短摘要作为确认请求 id（绑定「这一份包」，而非「随便哪份」）。 */
    private fun pendingRequestId(bytes: ByteArray): String =
        KeyboardPackageVerifier.sha256Hex(bytes).take(16)

    @JavascriptInterface
    fun confirmModelDownload(id: String, approved: Boolean, token: String) = guarded(token) {
        val result = modelDownloadGate.confirm(id, approved)
        when (result.action) {
            ModelDownloadGateAction.START -> {
                val model = modelStore.manifest().models.firstOrNull { it.id == id }
                when {
                    model == null -> pushModelError(id, "STALE_CONFIRMATION")
                    networkDecision() == NetworkDownloadDecision.NO_NETWORK -> pushModelError(id, "NO_NETWORK")
                    else -> startModelDownload(model, allowMetered = true)
                }
            }
            ModelDownloadGateAction.REJECTED -> Unit
            ModelDownloadGateAction.STALE,
            ModelDownloadGateAction.NO_NETWORK,
            ModelDownloadGateAction.ASK -> pushModelError(id, "STALE_CONFIRMATION")
        }
    }

    @JavascriptInterface
    fun cancelModelDownload(token: String) = guarded(token) {
        modelDownloadGate.cancelPending()
        synchronized(lifecycleLock) {
            modelStore.cancelDownload()
            modelDownloadNetwork?.cancelByUser()
        }
    }

    /** Called by SetupActivity after ACTION_OPEN_DOCUMENT returns. */
    fun importModelFromUri(id: String, uri: Uri) = synchronized(lifecycleLock) {
        if (closed) return
        val model = modelStore.manifest().models.firstOrNull { it.id == id }
        if (model == null) {
            pushModelError(id, "MODEL_IMPORT_FAILED", "未知模型")
            return
        }
        startModelImport(model, uri)
    }

    @JavascriptInterface
    fun deleteModel(id: String, token: String) = guarded(token) {
        val model = modelStore.manifest().models.firstOrNull { it.id == id } ?: return@guarded
        modelStore.delete(model)
        pushState()
    }

    @JavascriptInterface
    fun saveAsrSettings(stripPeriod: Boolean, hotwords: String, token: String) = guarded(token) {
        val lines = hotwords.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val kept = lines
            .filter { it.length <= AsrHotwords.MAX_CHARS_PER_LINE }
            .take(AsrHotwords.MAX_LINES)
        asrPrefs().edit()
            .putBoolean(AsrSettings.KEY_STRIP_FINAL_PERIOD, stripPeriod)
            .putString(AsrSettings.KEY_HOTWORDS, kept.joinToString("\n"))
            .apply()
        // Silently dropping over-long/extra lines looked like the
        // user's input vanishing - name what was kept instead.
        if (kept.size < lines.size) {
            pushEvent(
                JSONObject()
                    .put("type", "asrNote")
                    .put(
                        "message",
                        t(
                            context,
                            "已保存，但超长/超量的 ${lines.size - kept.size} 行热词被忽略（上限 ${AsrHotwords.MAX_LINES} 行，每行 ${AsrHotwords.MAX_CHARS_PER_LINE} 字）",
                            "Saved, but ${lines.size - kept.size} oversized or extra hotword lines were ignored (up to ${AsrHotwords.MAX_LINES} lines, ${AsrHotwords.MAX_CHARS_PER_LINE} characters per line)",
                        ),
                    )
            )
        }
        pushState()
    }

    /** Sub-page presence for the shell's BACK callback. */
    @JavascriptInterface
    fun reportPage(isSub: Boolean, token: String) = guarded(token) { onSubPage = isSub }

    /** The about page's one-tap version report. The clip is
     * flagged sensitive on API 33+ so the keyboard's clipboard history does
     * not absorb it; older releases just copy (flag unknown, never throws). */
    @JavascriptInterface
    fun copyText(text: String, token: String) = guarded(token) {
        // @JavascriptInterface runs on the WebView bridge thread; clipboard
        // focus checks are per-window, so marshal to the main thread like
        // the other host-visible actions.
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val clip = ClipData.newPlainText("feelime", text)
                if (Build.VERSION.SDK_INT >= 33) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                clipboardManager().setPrimaryClip(clip)
            }.onFailure { Log.w(TAG, "copyText failed", it) }
        }
    }

    private fun clipboardManager() =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** §15: the custom-keyboard table editor. Loose structural validation
     * here; the IME re-validates on adoption (single validator lives in
     * keyboard.js). */
    @JavascriptInterface
    fun saveCustom(json: String, enabled: Boolean, token: String) = guarded(token) {
        val parsed = runCatching { JSONObject(json) }.getOrNull()
        val rows = parsed?.optJSONArray("rows")
        if (parsed == null || parsed.optInt("version", 0) != 1 || rows == null) {
            pushEvent(
                JSONObject()
                    .put("type", "customError")
                    .put("code", "INVALID_CUSTOM_JSON")
                    .put("message", t(context, "JSON 需为 {\"version\":1,\"rows\":[[...]]}", "JSON must be {\"version\":1,\"rows\":[[...]]}")),
            )
            return@guarded
        }
        customKeysStore.save(json, enabled)
        pushState()
    }

    @JavascriptInterface
    fun checkUpdate(sourceUrl: String, token: String) = guarded(token) {
        val prefs = context.getSharedPreferences("keyboard_update", Context.MODE_PRIVATE)
        val entered = sourceUrl.trim()
        // An explicit empty value is the user's opt-out. It remains distinct
        // from an absent preference, which uses the official default source.
        prefs.edit().putString(KeyboardStore.STATE_SOURCE_URL, entered).apply()
        pushState()
        startUpdateCheck(entered.ifBlank { DEFAULT_GITHUB_SOURCE })
    }

    /** Run the opt-in startup check at most once per 24 hours. The timestamp
     * is written before the request, so failures and offline devices do not
     * cause a retry storm on every settings-page launch. */
    private fun maybeAutoCheck() {
        val prefs = context.getSharedPreferences("keyboard_update", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(STATE_AUTO_CHECK_ENABLED, false)) return
        val sourceConfigured = prefs.contains(KeyboardStore.STATE_SOURCE_URL)
        val savedSource = prefs.getString(KeyboardStore.STATE_SOURCE_URL, "") ?: ""
        if (sourceConfigured && savedSource.isBlank()) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong(STATE_AUTO_CHECK_LAST_ATTEMPT_AT, 0L)
        if (!UpdateCheckThrottle.isDue(
                enabled = true,
                sourceConfigured = sourceConfigured,
                source = savedSource,
                nowMs = now,
                lastAttemptMs = last,
            )) return
        prefs.edit().putLong(STATE_AUTO_CHECK_LAST_ATTEMPT_AT, now).apply()
        pushState()
        startUpdateCheck(if (sourceConfigured) savedSource else DEFAULT_GITHUB_SOURCE)
    }

    private fun startUpdateCheck(sourceUrl: String) {
        // Both channels may install a signed HTML/CSS/JS keyboard package.
        // Native APK updates have no path through this bridge; Play app
        // updates remain the store's responsibility.
        val source = sourceUrl.trim().ifBlank { DEFAULT_GITHUB_SOURCE }
        val github = GithubReleaseSource(HttpUrlConnectionFactory())
        worker.execute {
            runCatching {
                setState(KeyboardStore.STATE_UPDATE_STATE, "DOWNLOADING")
                pushState()
                if (github.accepts(source)) {
                    when (val result = github.fetch(source)) {
                        is GithubReleaseSource.GithubResult.Error -> failUpdate(result.code, result.message)
                        is GithubReleaseSource.GithubResult.Release -> {
                            if (result.isMetainfo) {
                                resolveAndInstallMetainfo(result.installUrl)
                            } else {
                                installResolved(result.installUrl)
                            }
                        }
                    }
                } else {
                    resolveAndInstallMetainfo(source)
                }
            }.onFailure { failure ->
                failUpdate("INTERNAL_ERROR", failure.javaClass.simpleName)
            }
        }
    }

    /** Metainfo update flow: uncached fetch → parse → install the zip it
     * points at (signature fragment pin intact). */
    private fun resolveAndInstallMetainfo(metainfoUrl: String) {
        val downloader = KeyboardUpdateDownloader(
            HttpUrlConnectionFactory(),
            allowHttp = BuildConfig.DEBUG && !BuildConfig.PLAY_DISTRIBUTION,
        )
        when (val meta = downloader.download(URI(metainfoUrl))) {
            is KeyboardUpdateDownloader.Result.Fail -> {
                failUpdate(meta.code.name, "metainfo: ${meta.detail.take(180)}")
            }
            is KeyboardUpdateDownloader.Result.Ok -> {
                val info = UpdateMetainfo.parse(String(meta.bytes, Charsets.UTF_8))
                if (info.url.isNullOrBlank()) {
                    failUpdate("INTERNAL_ERROR", "metainfo 里没有 url 字段")
                } else {
                    installResolved(info.url.trim())
                }
            }
        }
    }

    @JavascriptInterface
    fun installZip(url: String, token: String) = guarded(token) {
        if (url.isBlank()) {
            pushUpdateError(
                "MISSING_PACKAGE_URL",
                t(context, "请先填入键盘包地址", "Enter a keyboard package URL first"),
            )
            return@guarded
        }
        worker.execute {
            runCatching {
                installResolved(url.trim())
            }.onFailure { failure ->
                failUpdate("INTERNAL_ERROR", failure.javaClass.simpleName)
            }
        }
    }

    @JavascriptInterface
    fun restoreBuiltInKeyboard(token: String) = guarded(token) {
        worker.execute {
            runCatching {
                KeyboardUpdateCenter.store(context).restoreBuiltIn()
                setState(KeyboardStore.STATE_UPDATE_STATE, "BUILT_IN")
                KeyboardUpdateCenter.notifyUpdated(context)
            }.onFailure { Log.w(TAG, "restoreBuiltIn failed", it) }
            pushState()
        }
    }

    /** §16: play builds route update actions to the store listing. */
    private fun openPlayListing() {
        val pkg = context.packageName
        val intents = listOf(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg")),
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")),
        )
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // try the next transport
            }
        }
        // AOSP builds and restricted work profiles may provide neither a Play
        // market handler nor a browser. Make the failure visible instead of
        // turning the button into a silent no-op.
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                context,
                t(context, "未找到可打开应用商店的应用", "No app is available to open the app store"),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** The keyboard install flow (metainfo-resolved or direct zip URL). */
    // ---- §3 待确认包：整体发布/取走，避免 bridge 线程与 worker 交错 ----

    private fun invalidatePendingInstall() = synchronized(pendingLock) {
        pendingKeyboardInstall = null
    }

    /** 确认/取消按 id 整体取走；旧 id 只拒绝自身，不清掉更新的请求。 */
    private fun takePendingInstall(id: String): PendingKeyboardInstall? = synchronized(pendingLock) {
        val pending = pendingKeyboardInstall ?: return null
        if (id.isEmpty() || id != pending.id) return null
        pendingKeyboardInstall = null
        pending
    }

    private fun publishPendingInstall(install: PendingKeyboardInstall) = synchronized(pendingLock) {
        if (!closed) pendingKeyboardInstall = install
    }

    /** 设置页两条导入入口（本地 SAF 导入 / URL 下载安装）共用的安装：
     * SIGNATURE_BAD 时留包待确认，设置页弹确认后凭同一 id 走
     * confirmKeyboardInstall（设计 docs/design/userdata.md §3）；其余失败
     * 直接报错。每次新的安装尝试作废上一份待确认包：确认框永远只对应
     * 最近一次 SIGNATURE_BAD。 */
    private fun installWithConsent(bytes: ByteArray, fragmentPin: String? = null) {
        invalidatePendingInstall()
        when (val result = KeyboardUpdateCenter.store(context)
            .install(bytes, fragmentPin = fragmentPin)) {
            is KeyboardStore.InstallResult.Ok ->
                KeyboardUpdateCenter.notifyUpdated(context)
            is KeyboardStore.InstallResult.Fail -> {
                if (result.code == KeyboardUpdateErrorCode.SIGNATURE_BAD) {
                    val id = pendingRequestId(bytes)
                    publishPendingInstall(PendingKeyboardInstall(bytes, id, fragmentPin))
                    pushUpdateError(
                        result.code.name, result.detail,
                        confirmable = true, confirmId = id,
                    )
                } else {
                    pushUpdateError(result.code.name, result.detail)
                }
            }
        }
    }

    private fun installResolved(resolvedUrl: String) {
        val store = KeyboardUpdateCenter.store(context)
        val prefs = context.getSharedPreferences("keyboard_update", Context.MODE_PRIVATE)
        store.state.put(KeyboardStore.STATE_UPDATE_URL, resolvedUrl)
        // 接受新安装请求即作废旧待确认包（§3）：下载期间旧确认失效。
        invalidatePendingInstall()
        val url = URI(resolvedUrl)
        val fragmentPin = url.rawFragment
            ?.takeIf { it.startsWith("sha256=") }
            ?.removePrefix("sha256=")
        prefs.edit().putString(KeyboardStore.STATE_UPDATE_STATE, "DOWNLOADING").apply()
        pushState()
        val downloader = KeyboardUpdateDownloader(
            HttpUrlConnectionFactory(),
            allowHttp = BuildConfig.DEBUG && !BuildConfig.PLAY_DISTRIBUTION,
        )
        when (val download = downloader.download(url)) {
            is KeyboardUpdateDownloader.Result.Fail -> failUpdate(download.code.name, download.detail.take(200))
            is KeyboardUpdateDownloader.Result.Ok ->
                installWithConsent(download.bytes, fragmentPin)
        }
        pushState()
    }

    private fun setState(key: String, value: String) {
        context.getSharedPreferences("keyboard_update", Context.MODE_PRIVATE)
            .edit().putString(key, value).apply()
    }

    private fun failUpdate(code: String, detail: String) {
        // 任何导入/更新失败都作废待确认包（§3）：确认框只对应最近一次
        // SIGNATURE_BAD，失败后的旧确认请求一律不再有效。
        invalidatePendingInstall()
        val prefs = context.getSharedPreferences("keyboard_update", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KeyboardStore.STATE_LAST_ERROR_CODE, code)
            .putString(KeyboardStore.STATE_LAST_ERROR_MESSAGE, detail)
            .putString(KeyboardStore.STATE_UPDATE_STATE, "FAILED")
            .apply()
        // Worker failures use the same stable code/message protocol as
        // immediate validation failures.  Keep raw detail separate so it can
        // aid diagnosis without making the user-facing sentence language
        // dependent on whichever exception/network stack produced it.
        pushEvent(
            JSONObject()
                .put("type", "updateError")
                .put("code", code)
                .put("message", updateErrorMessage(code, ""))
                .put("detail", detail),
        )
        pushState()
    }

    private fun pushUpdateError(
        code: String,
        message: String,
        confirmable: Boolean = false,
        confirmId: String = "",
    ) {
        pushEvent(
            JSONObject()
                .put("type", "updateError")
                .put("code", code)
                .put("message", message)
                .put("confirmable", confirmable)
                .put("confirmId", confirmId),
        )
    }

    // ---- plumbing --------------------------------------------------------

    private fun guarded(token: String, body: () -> Unit) {
        if (closed) return
        if (pageToken.isEmpty() || token != pageToken) {
            Log.w(TAG, "settings bridge call rejected (bad token)")
            return
        }
        body()
    }

    private companion object {
        const val TAG = "FeelimeSettings"
        const val DEFAULT_GITHUB_SOURCE = "https://github.com/feelime/feelime"
        const val STATE_AUTO_CHECK_ENABLED = "update_auto_check_enabled"
        const val STATE_AUTO_CHECK_LAST_ATTEMPT_AT = "update_auto_check_last_attempt_at"

        /** 备份文件大小上限：userdb base64 后通常几百 KB，给到 64MB 防呆。 */
        const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
    }
}

/** Android may persist a component as either `pkg/.Service` or
 * `pkg/pkg.Service`; compare both forms without relying on a particular
 * Settings provider's flattening choice. */
internal fun matchesDefaultImeComponent(
    flat: String?,
    packageName: String,
    serviceClassName: String,
): Boolean {
    val value = flat?.trim().orEmpty()
    val slash = value.indexOf('/')
    if (slash <= 0 || slash == value.lastIndex) return false
    if (value.substring(0, slash) != packageName) return false
    val classPart = value.substring(slash + 1)
    val shortName = serviceClassName.removePrefix("$packageName.")
    return classPart == serviceClassName ||
        classPart == ".$shortName" ||
        classPart == shortName
}

/** Pure daily-check policy so the preference distinction is JVM-testable. */
internal object UpdateCheckThrottle {
    const val INTERVAL_MS = 24L * 60L * 60L * 1_000L

    fun isDue(
        enabled: Boolean,
        sourceConfigured: Boolean,
        source: String,
        nowMs: Long,
        lastAttemptMs: Long,
    ): Boolean {
        if (!enabled) return false
        if (sourceConfigured && source.isBlank()) return false
        // Zero is the persisted "never attempted" sentinel.  Keep any other
        // timestamp (including synthetic negative values used by JVM tests)
        // as a real attempt so a failed request still consumes the full day
        // budget instead of being retried immediately.
        if (lastAttemptMs == 0L) return true
        val elapsed = nowMs - lastAttemptMs
        return elapsed >= INTERVAL_MS
    }
}

/** design §15: native single source of truth for the custom keyboard
 * table (the IME WebView mirrors it through its bridge). */
class CustomKeysStore(private val context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun json(): String = prefs.getString(KEY_JSON, "") ?: ""

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun summary(): String = summary(null)

    fun summary(context: Context?): String {
        val raw = json()
        val noCustom = if (context == null) "未定制" else t(context, "未定制", "No custom keys")
        if (raw.isEmpty()) return noCustom
        val rows = runCatching { JSONObject(raw).optJSONArray("rows") }.getOrNull() ?: return noCustom
        var count = 0
        for (row in rows.iterate()) count += row.length()
        return if (context == null) "已定制 $count 个键"
        else t(context, "已定制 $count 个键", "$count custom keys")
    }

    fun save(json: String, enabled: Boolean) {
        prefs.edit().putString(KEY_JSON, json).putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun JSONArray.iterate(): Sequence<JSONArray> = sequence {
        for (index in 0 until length()) yield(optJSONArray(index) ?: JSONArray())
    }

    private companion object {
        const val PREFS = "feelime_custom_keys"
        const val KEY_JSON = "json"
        const val KEY_ENABLED = "enabled"
    }
}
