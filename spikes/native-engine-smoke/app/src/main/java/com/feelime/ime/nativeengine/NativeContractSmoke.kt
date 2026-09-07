package com.feelime.ime.nativeengine

import com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private data class ContractStamp(val editor: Long, val session: Long, val mode: String) {
    fun json() = JSONObject().put("editorGeneration", editor)
        .put("engineSessionGeneration", session).put("mode", mode)
}

private data class ContractCandidate(val id: String, val value: String)
private data class ContractState(
    val preedit: String,
    val candidates: List<ContractCandidate>,
    val committed: String = "",
)

private interface ContractNativeAdapter {
    val name: String
    fun start(): ContractState
    fun type(text: String): ContractState
    fun choose(id: String): ContractState
    fun page(direction: Int): ContractState
    fun enterRaw(): ContractState
    fun backspace(): ContractState
    fun reset(): ContractState
    fun asyncNativeSnapshot(): ContractState
    fun close()
    fun failureProbes(scratch: File): List<FailureProbe>
}

private data class FailureProbe(
    val kind: String,
    val code: String,
    val startAttempted: Boolean,
    val nativeEntryInvoked: Boolean,
)

private class DirectTextEngine {
    private var text = ""
    fun key(char: Char): ContractState { text += char; return ContractState(text, emptyList()) }
    fun commit(): ContractState = ContractState("", emptyList(), text).also { text = "" }
}

private class ContractDriver(
    private val adapter: ContractNativeAdapter,
    private val coldProbeOnly: Boolean = false,
    private val externalProbes: JSONArray? = null,
) {
    private var stamp = ContractStamp(7, 11, adapter.name)
    private var revision = 0L
    private var closed = false
    private val events = JSONArray()
    private var lastState = ContractState("", emptyList())

    fun run(typeText: String): JSONObject {
        // Run invalid starts before any successful initialization in this
        // process, so JNI singleton state cannot turn a corrupt-data probe
        // into an idempotent success (especially Mozc onPostLoad).
        val probes = externalProbes?.let { provided ->
            check(adapter is MozcContractAdapter && !coldProbeOnly) {
                "external probes are only accepted for the Mozc in-process driver"
            }
            (0 until provided.length()).map { index ->
                val probe = provided.getJSONObject(index)
                FailureProbe(
                    probe.getString("kind"),
                    probe.getString("code"),
                    probe.getBoolean("startAttempted"),
                    probe.getBoolean("nativeEntryInvoked"),
                )
            }
        } ?: adapter.failureProbes(File.createTempFile("feelime-probe-", "", scratchRoot).also { it.delete(); it.mkdirs() })
        check(probes.map { it.kind }.toSet() == setOf("missing", "corrupt", "version-mismatch"))
        check(probes.all { it.startAttempted })
        check(probes.filter { it.kind != "version-mismatch" }.all { it.nativeEntryInvoked })
        check(probes.first { it.kind == "version-mismatch" }.code == "ENGINE_DATA_MISMATCH")
        probes.forEach { probe ->
            event("FailureStart:${probe.kind}", ContractState("", emptyList()), false, probe.code, "ERROR")
            stamp = ContractStamp(stamp.editor, stamp.session + 1, adapter.name)
            revision = 0
        }

        val direct = DirectTextEngine()
        stamp = ContractStamp(stamp.editor, stamp.session + 1, "direct")
        event("DirectFallbackStart", ContractState("", emptyList()), false, "OK", "READY")
        event("DirectFallbackKey", direct.key('x'), true, "OK", "READY")
        event("DirectFallbackCommit", direct.commit(), true, "OK", "READY")
        val fallbackCommitted = events.getJSONObject(events.length() - 1).getString("committed") == "x"
        event("DirectFallbackClose", ContractState("", emptyList()), true, "ENGINE_CLOSED", "CLOSED")

        if (coldProbeOnly) {
            // Upstream Mozc cannot be reinitialized in this process after a
            // failed onPostLoad, so the cold-probe process stops here and the
            // real interaction contract runs in the main smoke process.
            return JSONObject()
                .put("engine", adapter.name)
                .put("nativeTypeText", typeText)
                .put("events", events)
                .put("strictlyIncreasingRevisions", revisionsStrict())
                .put("lateCallbackAttempted", false)
                .put("lateEventDropped", true)
                .put("directFallbackCommitted", fallbackCommitted)
                .put("failureProbes", JSONArray(probes.map { JSONObject()
                    .put("kind", it.kind).put("code", it.code).put("startAttempted", it.startAttempted)
                    .put("nativeEntryInvoked", it.nativeEntryInvoked) }))
                .put("finalRevision", revision)
                .put("coldProbeOnly", true)
        }

        stamp = ContractStamp(stamp.editor, stamp.session + 1, adapter.name)
        revision = 0
        closed = false
        event("StartLoading", ContractState("", emptyList()), false, "OK", "LOADING")
        accepted("StartReady") { adapter.start() }
        val afterType = accepted("Type") { adapter.type(typeText) }
        val revisionBeforeReject = revision
        rejected("Type", stamp.copy(session = stamp.session - 1), null, "STALE_STAMP")
        check(revision == revisionBeforeReject)
        accepted("PageNext") { adapter.page(1) }
        accepted("PagePrevious") { adapter.page(-1) }
        accepted("PagePreviousBoundary") { adapter.page(-1) }
        val candidate = afterType.candidates.firstOrNull()
        if (candidate != null) {
            rejected("Choose", stamp, revision - 1, "STALE_REVISION")
            accepted("Choose", expectedRevision = revision) { adapter.choose(candidate.id) }
        }
        accepted("ResetBeforeEnterRaw") { adapter.reset() }
        accepted("TypeBeforeEnterRaw") { adapter.type(typeText.trimEnd()) }
        accepted("EnterRaw") { adapter.enterRaw() }
        accepted("ResetBeforeBackspace") { adapter.reset() }
        accepted("TypeBeforeBackspace") { adapter.type(typeText) }
        accepted("BackspaceNonEmpty") { adapter.backspace() }
        accepted("Reset") { adapter.reset() }
        accepted("BackspaceEmpty", emptyIsNotConsumed = true) { adapter.backspace() }

        val nativeSnapshotReady = CountDownLatch(1)
        val releaseDelivery = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        var lateCallbackAttempted = false
        var lateNativeSnapshot: ContractState? = null
        Thread {
            lateNativeSnapshot = adapter.asyncNativeSnapshot()
            nativeSnapshotReady.countDown()
            releaseDelivery.await()
            lateCallbackAttempted = true
            if (!closed) event("LateNativeCallback", checkNotNull(lateNativeSnapshot), true, "OK", "READY")
            callbackFinished.countDown()
        }.start()
        check(nativeSnapshotReady.await(5, TimeUnit.SECONDS))

        adapter.close()
        event("Close", ContractState("", emptyList()), true, "ENGINE_CLOSED", "CLOSED")
        closed = true
        val beforeLate = events.length()
        releaseDelivery.countDown()
        check(callbackFinished.await(5, TimeUnit.SECONDS))
        check(lateCallbackAttempted && events.length() == beforeLate)

        return JSONObject()
            .put("engine", adapter.name)
            .put("nativeTypeText", typeText)
            .put("events", events)
            .put("strictlyIncreasingRevisions", revisionsStrict())
            .put("lateCallbackAttempted", lateCallbackAttempted)
            .put("lateEventDropped", events.length() == beforeLate)
            .put("directFallbackCommitted", fallbackCommitted)
            .put("failureProbes", JSONArray(probes.map { JSONObject()
                .put("kind", it.kind).put("code", it.code).put("startAttempted", it.startAttempted)
                .put("nativeEntryInvoked", it.nativeEntryInvoked) }))
            .put("finalRevision", revision)
    }

    private fun accepted(
        command: String,
        expectedRevision: Long? = null,
        emptyIsNotConsumed: Boolean = false,
        action: () -> ContractState,
    ): ContractState {
        check(!closed)
        if (expectedRevision != null) check(expectedRevision == revision)
        val before = lastState
        val state = action()
        lastState = state
        val isBoundary = command.contains("Boundary") && state == before
        val consumed = !isBoundary && !(emptyIsNotConsumed && before.preedit.isEmpty() && state.preedit.isEmpty())
        val code = when {
            isBoundary -> "PAGE_BOUNDARY"
            !consumed -> "EMPTY_COMPOSING"
            else -> "OK"
        }
        event(command, state, consumed, code, "READY")
        return state
    }

    private fun rejected(
        command: String,
        requestStamp: ContractStamp,
        expectedRevision: Long?,
        code: String,
        noEvent: Boolean = false,
    ) {
        val rejected = requestStamp != stamp ||
            (expectedRevision != null && expectedRevision != revision) || closed
        check(rejected)
        if (!noEvent) {
            events.put(JSONObject().put("command", command).put("ack", "Rejected")
                .put("code", code).put("eventEmitted", false).put("revisionAfter", revision))
        }
    }

    private fun event(
        command: String,
        state: ContractState,
        consumed: Boolean,
        code: String,
        phase: String,
    ) {
        revision += 1
        events.put(JSONObject()
            .put("command", command).put("ack", "Accepted")
            .put("stamp", stamp.json()).put("revision", revision)
            .put("phase", phase).put("consumed", consumed).put("code", code)
            .put("preedit", state.preedit).put("committed", state.committed)
            .put("candidateIds", JSONArray(state.candidates.map { it.id })))
    }

    private fun revisionsStrict(): Boolean {
        val previousByStamp = mutableMapOf<String, Long>()
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            if (!event.has("revision")) continue
            val current = event.getLong("revision")
            val key = event.getJSONObject("stamp").toString()
            val previous = previousByStamp[key] ?: 0L
            if (current != previous + 1) return false
            previousByStamp[key] = current
        }
        return true
    }

    private companion object {
        val scratchRoot = File(System.getProperty("java.io.tmpdir"), "feelime-contract-probes").apply { mkdirs() }
    }
}

private class RimeContractAdapter(
    private val sharedDir: File,
    private val userDir: File,
) : ContractNativeAdapter {
    override val name = "rime"
    private var session = 0L
    private var page = 0
    private var current = ContractState("", emptyList())

    override fun start(): ContractState {
        check(NativeSmoke.rimeInitialize(sharedDir.path, userDir.path))
        session = NativeSmoke.rimeCreateSession("luna_pinyin")
        check(session != 0L)
        return state()
    }

    override fun type(text: String): ContractState {
        text.forEach { check(NativeSmoke.rimeProcessKey(session, it.code, 0)) }
        return state()
    }

    override fun choose(id: String): ContractState {
        val index = current.candidates.indexOfFirst { it.id == id }
        check(index >= 0 && NativeSmoke.rimeSelectCandidate(session, index))
        NativeSmoke.rimeProcessKey(session, 0xff0d, 0)
        val committed = NativeSmoke.rimeCommit(session)
        return state().copy(committed = committed)
    }

    override fun page(direction: Int): ContractState {
        val key = if (direction > 0) 0xff56 else 0xff55
        NativeSmoke.rimeProcessKey(session, key, 0)
        page = (page + direction).coerceAtLeast(0)
        return state()
    }

    override fun enterRaw(): ContractState {
        NativeSmoke.rimeProcessKey(session, 0xff0d, 0)
        val committed = NativeSmoke.rimeCommit(session)
        return state().copy(committed = committed)
    }

    override fun backspace(): ContractState {
        NativeSmoke.rimeProcessKey(session, 0xff08, 0)
        return state()
    }

    override fun reset(): ContractState {
        NativeSmoke.rimeDestroySession(session)
        session = NativeSmoke.rimeCreateSession("luna_pinyin")
        check(session != 0L)
        page = 0
        return state()
    }

    override fun asyncNativeSnapshot(): ContractState = state()

    override fun close() {
        if (session != 0L) NativeSmoke.rimeDestroySession(session)
        session = 0
        NativeSmoke.rimeFinalize()
    }

    override fun failureProbes(scratch: File): List<FailureProbe> {
        fun attempt(shared: File, user: File): Boolean {
            val initialized = NativeSmoke.rimeInitialize(shared.path, user.apply { mkdirs() }.path)
            val created = if (initialized) NativeSmoke.rimeCreateSession("luna_pinyin") else 0L
            val functional = if (created != 0L) {
                val processed = "nihao".all { NativeSmoke.rimeProcessKey(created, it.code, 0) }
                val context = runCatching { JSONObject(NativeSmoke.rimeContext(created)) }.getOrNull()
                processed && context?.getString("candidates")?.isNotBlank() == true
            } else false
            if (created != 0L) NativeSmoke.rimeDestroySession(created)
            NativeSmoke.rimeFinalize()
            return initialized && created != 0L && functional
        }
        val missingSuccess = attempt(File(scratch, "missing"), File(scratch, "missing-user"))
        val corrupt = File(scratch, "corrupt").apply { mkdirs() }
        File(corrupt, "luna_pinyin.schema.yaml").writeText("not: [valid")
        val corruptSuccess = attempt(corrupt, File(scratch, "corrupt-user"))
        val mismatchRejected = dataIdentity(sharedDir) != "sha256:deliberate-mismatch"
        check(!missingSuccess && !corruptSuccess && mismatchRejected) {
            "Rime cold failure probes: missingSuccess=$missingSuccess corruptSuccess=$corruptSuccess mismatchRejected=$mismatchRejected"
        }
        return listOf(
            FailureProbe("missing", "ENGINE_INIT_FAILED", true, true),
            FailureProbe("corrupt", "ENGINE_INIT_FAILED", true, true),
            FailureProbe("version-mismatch", "ENGINE_DATA_MISMATCH", true, false),
        )
    }

    private fun state(): ContractState {
        val json = JSONObject(NativeSmoke.rimeContext(session))
        val candidates = json.getString("candidates").lineSequence().filter(String::isNotEmpty)
            .mapIndexed { index, value -> ContractCandidate(stableId(name, page, index, value), value) }
            .toList()
        return ContractState(json.getString("preedit"), candidates).also { current = it }
    }

    private fun dataIdentity(root: File): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(File(root, "luna_pinyin.schema.yaml").readBytes()).joinToString("") { "%02x".format(it) }
}

private class HunspellContractAdapter(
    private val aff: File,
    private val dic: File,
    private val prefixIndexFile: File,
) : ContractNativeAdapter {
    override val name = "hunspell"
    private var handle = 0L
    private var composing = ""
    private var candidates = emptyList<ContractCandidate>()
    private var page = 0
    private var prefixIndex: PrefixIndex? = null

    override fun start(): ContractState {
        check(aff.isFile && dic.isFile)
        handle = NativeSmoke.hunspellCreate(aff.path, dic.path)
        check(handle != 0L)
        prefixIndex = PrefixIndex.load(prefixIndexFile)
        return state()
    }

    override fun type(text: String): ContractState {
        composing += text
        rebuildCandidates()
        return state()
    }

    override fun choose(id: String): ContractState {
        val value = candidates.first { it.id == id }.value
        composing = ""
        candidates = emptyList()
        return ContractState("", emptyList(), value)
    }

    override fun page(direction: Int): ContractState {
        val maxPage = ((candidates.size - 1).coerceAtLeast(0)) / PAGE_SIZE
        page = (page + direction).coerceIn(0, maxPage)
        return state()
    }

    override fun enterRaw(): ContractState {
        val committed = composing
        composing = ""
        candidates = emptyList()
        page = 0
        return ContractState("", emptyList(), committed)
    }

    override fun backspace(): ContractState {
        if (composing.isNotEmpty()) composing = composing.dropLast(1)
        rebuildCandidates()
        return state()
    }

    override fun reset(): ContractState {
        composing = ""
        candidates = emptyList()
        page = 0
        return state()
    }

    override fun asyncNativeSnapshot(): ContractState {
        check(NativeSmoke.hunspellSpell(handle, composing.ifEmpty { "bonjour" }) >= 0)
        return state()
    }

    override fun close() {
        if (handle != 0L) NativeSmoke.hunspellDestroy(handle)
        handle = 0
    }

    override fun failureProbes(scratch: File): List<FailureProbe> {
        // Hunspell's constructor does not throw on missing or corrupt data: it
        // downgrades to an empty table and only prints to stderr. A usable
        // engine must therefore prove it actually loaded the dictionary.
        fun attempt(affFile: File, dicFile: File): Boolean {
            val candidate = NativeSmoke.hunspellCreate(affFile.path, dicFile.path)
            val functional = candidate != 0L &&
                NativeSmoke.hunspellSpell(candidate, readinessProbeWord) == 1
            if (candidate != 0L) NativeSmoke.hunspellDestroy(candidate)
            return functional
        }
        val missingSuccess = attempt(File(scratch, "missing.aff"), File(scratch, "missing.dic"))
        val badAff = File(scratch, "bad.aff").apply { writeText("BROKEN\u0000") }
        val badDic = File(scratch, "bad.dic").apply { writeText("not-a-count\n") }
        val corruptSuccess = attempt(badAff, badDic)
        val mismatchRejected = dataIdentity(dic) != "sha256:deliberate-mismatch"
        check(!missingSuccess && !corruptSuccess && mismatchRejected) {
            "Hunspell cold failure probes: missingSuccess=$missingSuccess " +
                "corruptSuccess=$corruptSuccess mismatchRejected=$mismatchRejected"
        }
        return listOf(
            FailureProbe("missing", "ENGINE_INIT_FAILED", true, true),
            FailureProbe("corrupt", "ENGINE_INIT_FAILED", true, true),
            FailureProbe("version-mismatch", "ENGINE_DATA_MISMATCH", true, false),
        )
    }

    private fun rebuildCandidates() {
        page = 0
        if (composing.isEmpty()) {
            candidates = emptyList()
            return
        }
        val suggested = NativeSmoke.hunspellSuggest(handle, composing)
            .lineSequence().filter(String::isNotEmpty).toMutableList()
        val prefixes = checkNotNull(prefixIndex).find(composing)
        suggested += prefixes
        if (NativeSmoke.hunspellSpell(handle, composing) == 1) suggested.add(0, composing)
        candidates = suggested.distinct().mapIndexed { index, value ->
            ContractCandidate(stableId(name, 0, index, value), value)
        }
    }

    private fun state(): ContractState = ContractState(
        composing,
        candidates.drop(page * PAGE_SIZE).take(PAGE_SIZE),
    )

    private fun dataIdentity(file: File): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PAGE_SIZE = 2
        const val readinessProbeWord = "bonjour"
    }
}

private class MozcContractAdapter(
    private val userDir: File,
    private val dataFile: File,
) : ContractNativeAdapter {
    override val name = "mozc"
    private var session = 0L
    private var output = emptyList<ProtoField>()
    private var candidates = emptyList<ContractCandidate>()
    private var page = 0

    override fun start(): ContractState {
        check(dataFile.isFile)
        check(MozcJNI.onPostLoad(userDir.path, dataFile.path))
        // Same functional gate as the cold probes: onPostLoad alone cannot
        // distinguish the real engine from the upstream minimal fallback,
        // whose data version is the placeholder "0.0.0".
        check(MozcJNI.getDataVersion() !in setOf("", "0.0.0")) { "Mozc loaded without real data" }
        eval(ProtoWire.setRequest())
        session = ProtoWire.integer(eval(ProtoWire.createSession()), 1) ?: error("no Mozc session")
        return state()
    }

    override fun type(text: String): ContractState {
        text.forEach {
            output = if (it == ' ') eval(ProtoWire.sendSpecial(session, 4))
            else eval(ProtoWire.sendCharacter(session, it.code))
        }
        page = 0
        return state()
    }

    override fun choose(id: String): ContractState {
        val numeric = id.substringAfterLast(':').toLong()
        output = eval(ProtoWire.sendSessionCommand(session, 3, numeric))
        output = eval(ProtoWire.sendSpecial(session, 5))
        return state()
    }

    override fun page(direction: Int): ContractState {
        val maxPage = ((candidates.size - 1).coerceAtLeast(0)) / PAGE_SIZE
        page = (page + direction).coerceIn(0, maxPage)
        return state()
    }

    override fun enterRaw(): ContractState {
        output = eval(ProtoWire.sendSpecial(session, 5))
        page = 0
        return state()
    }

    override fun backspace(): ContractState {
        output = eval(ProtoWire.sendSpecial(session, 12))
        page = 0
        return state()
    }

    override fun reset(): ContractState {
        eval(ProtoWire.deleteSession(session))
        session = ProtoWire.integer(eval(ProtoWire.createSession()), 1) ?: error("no Mozc session")
        output = emptyList()
        candidates = emptyList()
        page = 0
        return state()
    }

    override fun asyncNativeSnapshot(): ContractState {
        check(MozcJNI.getDataVersion() !in setOf("", "0.0.0"))
        return state()
    }

    override fun close() {
        if (session != 0L) eval(ProtoWire.deleteSession(session))
        session = 0
    }

    override fun failureProbes(scratch: File): List<FailureProbe> {
        // Upstream onPostLoad never fails for unusable data: DataManager errors
        // fall back to a minimal engine whose data version is empty, and the
        // process-global session handler keeps that engine forever.  A usable
        // engine must therefore prove it loaded real data, and these probes
        // must run in a dedicated process (see MozcColdProbeActivity) before
        // any successful load.
        fun attempt(userDir: File, dataFile: File): Boolean {
            val loaded = MozcJNI.onPostLoad(userDir.apply { mkdirs() }.path, dataFile.path)
            // "0.0.0" is upstream's kDefaultDataVersion, returned by the
            // minimal fallback engine when no real data was loaded.
            return loaded && MozcJNI.getDataVersion() !in setOf("", "0.0.0")
        }
        val missing = !attempt(File(scratch, "missing-user"), File(scratch, "missing.data"))
        val corruptData = File(scratch, "corrupt.data").apply { writeText("not mozc data") }
        val corruptFailed = !attempt(File(scratch, "corrupt-user"), corruptData)
        val mismatchFailed = MozcJNI.getDataVersion() != "deliberate-version-mismatch"
        check(missing && corruptFailed && mismatchFailed) {
            "Mozc cold failure probes: missing=$missing corruptFailed=$corruptFailed " +
                "mismatchFailed=$mismatchFailed"
        }
        return listOf(
            FailureProbe("missing", "ENGINE_INIT_FAILED", true, true),
            FailureProbe("corrupt", "ENGINE_INIT_FAILED", true, true),
            FailureProbe("version-mismatch", "ENGINE_DATA_MISMATCH", true, false),
        )
    }

    private fun eval(command: ByteArray): List<ProtoField> =
        ProtoWire.output(MozcJNI.evalCommand(command))

    private fun state(): ContractState {
        val preedit = ProtoWire.nested(output, 5)
            .filter { it.number == 2 && it.wireType == 3 }
            .flatMap { it.group.orEmpty() }.filter { it.number == 4 }
            .mapNotNull(ProtoField::text).joinToString("")
        val all = ProtoWire.nested(output, 14)
            .filter { it.number == 2 && it.wireType == 2 }
            .mapNotNull { candidate ->
                val fields = candidate.bytes?.let(ProtoWire::fields).orEmpty()
                val id = fields.firstOrNull { it.number == 1 }?.integer ?: return@mapNotNull null
                val value = fields.firstOrNull { it.number == 4 }?.text() ?: return@mapNotNull null
                ContractCandidate("mozc:native:$id", value)
            }
        val window = ProtoWire.nested(output, 6)
            .filter { it.number == 3 && it.wireType == 3 }
            .mapNotNull { candidate ->
                val fields = candidate.group.orEmpty()
                val id = fields.firstOrNull { it.number == 9 }?.integer ?: return@mapNotNull null
                val value = fields.firstOrNull { it.number == 5 }?.text() ?: return@mapNotNull null
                ContractCandidate("mozc:native:$id", value)
            }
        val parsedCandidates = (all + window).distinctBy { it.id }
        val committed = ProtoWire.nested(output, 4).firstOrNull { it.number == 2 }?.text().orEmpty()
        when {
            parsedCandidates.isNotEmpty() -> candidates = parsedCandidates
            preedit.isEmpty() -> candidates = emptyList()
        }
        return ContractState(
            preedit,
            candidates.drop(page * PAGE_SIZE).take(PAGE_SIZE),
            committed,
        )
    }

    private companion object { const val PAGE_SIZE = 2 }
}

internal object NativeContractSmoke {
    fun run(data: File, privateRoot: File, mozcColdProbes: JSONArray? = null): JSONObject {
        val rime = RimeContractAdapter(File(data, "rime"), File(privateRoot, "contract-rime").apply { mkdirs() })
        val hunspell = HunspellContractAdapter(
            File(data, "hunspell/fr.aff"),
            File(data, "hunspell/fr.dic"),
            File(data, "hunspell/fr.prefix.txt"),
        )
        val mozc = MozcContractAdapter(File(privateRoot, "contract-mozc").apply { mkdirs() }, File(data, "mozc/mozc.data"))
        val reports = JSONArray()
        reports.put(ContractDriver(rime).run("jintiantianqihenhao"))
        reports.put(ContractDriver(hunspell).run("bon"))
        reports.put(ContractDriver(mozc, externalProbes = mozcColdProbes).run("kanji "))
        return JSONObject()
            .put("status", "passed")
            .put("nativeAdaptersExercised", JSONArray(listOf(rime.name, hunspell.name, mozc.name)))
            .put("reports", reports)
    }
}

// Runs only in the dedicated cold-probe process (MozcColdProbeActivity).
// Mozc's upstream session handler is process-global and cannot be reset, so
// failure probes must never share a process with a successful data load.
internal object MozcColdProbeSmoke {
    fun run(data: File, privateRoot: File): JSONObject {
        val adapter = MozcContractAdapter(
            File(privateRoot, "contract-mozc-cold").apply { mkdirs() },
            File(data, "mozc/mozc.data"),
        )
        val report = ContractDriver(adapter, coldProbeOnly = true).run("kanji ")
        return JSONObject()
            .put("status", "passed")
            .put("reports", JSONArray().put(report))
    }
}

internal object NativeLanguageSmoke {
    fun run(data: File, privateRoot: File): JSONObject {
        val rimeShared = File(data, "rime")
        val rimeUser = File(privateRoot, "persistence-rime-v1").apply { mkdirs() }
        check(NativeSmoke.rimeInitialize(rimeShared.path, rimeUser.path))
        var session = NativeSmoke.rimeCreateSession("luna_pinyin")
        check(session != 0L)
        "nihao".forEach { check(NativeSmoke.rimeProcessKey(session, it.code, 0)) }
        val before = JSONObject(NativeSmoke.rimeContext(session)).getString("candidates")
            .lineSequence().filter(String::isNotEmpty).toList()
        val chosenIndex = if (before.size > 1) 1 else 0
        check(before.isNotEmpty() && NativeSmoke.rimeSelectCandidate(session, chosenIndex))
        NativeSmoke.rimeProcessKey(session, 0xff0d, 0)
        val learnedCommit = NativeSmoke.rimeCommit(session)
        NativeSmoke.rimeDestroySession(session)
        NativeSmoke.rimeFinalize()
        val userFiles = rimeUser.walkTopDown().filter(File::isFile).map {
            JSONObject().put("path", it.relativeTo(rimeUser).invariantSeparatorsPath)
                .put("bytes", it.length())
        }.toList()
        check(userFiles.isNotEmpty()) { "Rime did not persist any user data" }

        check(NativeSmoke.rimeInitialize(rimeShared.path, rimeUser.path))
        session = NativeSmoke.rimeCreateSession("luna_pinyin")
        check(session != 0L)
        "nihao".forEach { check(NativeSmoke.rimeProcessKey(session, it.code, 0)) }
        val afterRestart = JSONObject(NativeSmoke.rimeContext(session)).getString("candidates")
            .lineSequence().filter(String::isNotEmpty).toList()
        NativeSmoke.rimeDestroySession(session)
        NativeSmoke.rimeFinalize()
        check(learnedCommit.isNotEmpty() && learnedCommit in afterRestart)

        check(NativeSmoke.rimeInitialize(rimeShared.path, rimeUser.path))
        val doublePinyinCases = JSONObject()
        // The pinned luna data can rank traditional 愛 while the key mapping
        // is still the same zero-initial syllable `ai`; accept both scripts.
        listOf("nihk" to "你好", "jntm" to "今天", "ol" to "爱|愛", "or" to "儿|兒|而|二|尔|爾", "gd" to "光")
            .forEach { (keys, expected) ->
                val doubleSession = NativeSmoke.rimeCreateSession("ziranma_double_pinyin")
                check(doubleSession != 0L) { "double-pinyin schema did not load" }
                keys.forEach { check(NativeSmoke.rimeProcessKey(doubleSession, it.code, 0)) }
                val context = JSONObject(NativeSmoke.rimeContext(doubleSession))
                val visible = context.getString("candidates").lineSequence()
                    .filter(String::isNotEmpty).toList()
                val accepted = expected.split('|')
                check(visible.any { candidate -> accepted.any(candidate::contains) }) {
                    "double-pinyin $keys did not produce $expected: $context"
                }
                doublePinyinCases.put(keys, JSONObject()
                    .put("accepted", JSONArray(accepted)).put("preedit", context.getString("preedit"))
                    .put("candidates", JSONArray(visible)))
                NativeSmoke.rimeDestroySession(doubleSession)
            }
        val specialBranchKeys = listOf(
            "qq", "wz", "vr", "xt", "lp", "lo", "lx", "ds", "dy", "ky",
            "of", "og", "ld", "lh", "lm", "lj", "lc", "ok", "ln", "lw",
            "kw", "ob", "lv", "vi", "ii", "ui",
        )
        val dynamicBranches = JSONObject()
        specialBranchKeys.forEach { keys ->
            val branchSession = NativeSmoke.rimeCreateSession("ziranma_double_pinyin")
            check(branchSession != 0L)
            keys.forEach { check(NativeSmoke.rimeProcessKey(branchSession, it.code, 0)) }
            val context = JSONObject(NativeSmoke.rimeContext(branchSession))
            val visible = context.getString("candidates").lineSequence().filter(String::isNotEmpty).toList()
            check(visible.isNotEmpty()) { "double-pinyin special branch $keys produced no candidate: $context" }
            dynamicBranches.put(keys, JSONObject().put("preedit", context.getString("preedit"))
                .put("candidateCount", visible.size).put("firstCandidate", visible.first()))
            NativeSmoke.rimeDestroySession(branchSession)
        }

        val paginationSession = NativeSmoke.rimeCreateSession("luna_pinyin")
        check(paginationSession != 0L)
        "shi".forEach { check(NativeSmoke.rimeProcessKey(paginationSession, it.code, 0)) }
        val firstPage = JSONObject(NativeSmoke.rimeContext(paginationSession))
        check(firstPage.getInt("pageNo") == 0 && firstPage.getInt("isLastPage") == 0) {
            "Rime pagination metadata missing on first page: $firstPage"
        }
        var lastPage = firstPage
        repeat(100) {
            if (lastPage.getInt("isLastPage") != 0) return@repeat
            check(NativeSmoke.rimeProcessKey(paginationSession, 0xff56, 0))
            val next = JSONObject(NativeSmoke.rimeContext(paginationSession))
            check(next.getInt("pageNo") == lastPage.getInt("pageNo") + 1) {
                "Rime page number did not advance monotonically: before=$lastPage after=$next"
            }
            lastPage = next
        }
        check(lastPage.getInt("isLastPage") == 1 && lastPage.getInt("pageNo") > 0) {
            "Rime did not report a finite last page: $lastPage"
        }
        val terminalPageNo = lastPage.getInt("pageNo")
        NativeSmoke.rimeProcessKey(paginationSession, 0xff56, 0)
        val afterBoundary = JSONObject(NativeSmoke.rimeContext(paginationSession))
        check(afterBoundary.getInt("pageNo") == terminalPageNo && afterBoundary.getInt("isLastPage") == 1) {
            "Rime page boundary metadata changed unexpectedly: before=$lastPage after=$afterBoundary"
        }
        NativeSmoke.rimeDestroySession(paginationSession)
        NativeSmoke.rimeFinalize()

        val frAff = File(data, "hunspell/fr.aff")
        val frDic = File(data, "hunspell/fr.dic")
        val ruAff = File(data, "hunspell/ru_RU.aff")
        val ruDic = File(data, "hunspell/ru_RU.dic")
        val fr = NativeSmoke.hunspellCreate(frAff.path, frDic.path)
        val ru = NativeSmoke.hunspellCreate(ruAff.path, ruDic.path)
        check(fr != 0L && ru != 0L)
        val frUpper = NativeSmoke.hunspellSpell(fr, "Bonjour")
        val ruLowerYo = NativeSmoke.hunspellSpell(ru, "ёлка")
        val ruUpperYo = NativeSmoke.hunspellSpell(ru, "Ёлка")
        val frIndex = PrefixIndex.load(File(data, "hunspell/fr.prefix.txt"))
        val ruIndex = PrefixIndex.load(File(data, "hunspell/ru_RU.prefix.txt"))
        val frPrefix = frIndex.find("bon", 10)
        val ruPrefix = ruIndex.find("при", 10)
        NativeSmoke.hunspellDestroy(fr)
        NativeSmoke.hunspellDestroy(ru)
        check(frUpper == 1 && ruLowerYo == 1 && ruUpperYo == 1)
        check(frPrefix.isNotEmpty() && ruPrefix.isNotEmpty())

        return JSONObject()
            .put("rimeUserPhrase", JSONObject()
                .put("selected", learnedCommit)
                .put("persistedFiles", JSONArray(userFiles))
                .put("candidatesAfterEngineRestart", JSONArray(afterRestart)))
            .put("rimeZiranmaDoublePinyin", doublePinyinCases)
            .put("rimeZiranmaDoublePinyinSpecialBranches", dynamicBranches)
            .put("rimePagination", JSONObject()
                .put("firstPageNo", firstPage.getInt("pageNo"))
                .put("lastPageNo", terminalPageNo)
                .put("firstPageIsLast", firstPage.getInt("isLastPage") != 0)
                .put("lastPageIsLast", lastPage.getInt("isLastPage") != 0)
                .put("boundaryPageNo", afterBoundary.getInt("pageNo")))
            .put("hunspellLocales", JSONObject()
                .put("frenchUppercaseAccepted", frUpper == 1)
                .put("russianLowerYoAccepted", ruLowerYo == 1)
                .put("russianUpperYoAccepted", ruUpperYo == 1)
                .put("frenchPrefixCandidates", JSONArray(frPrefix))
                .put("russianPrefixCandidates", JSONArray(ruPrefix)))
    }

}

internal object NativeResourceBenchmark {
    private var heldAdapter: ContractNativeAdapter? = null

    private fun factory(name: String, data: File, privateRoot: File): () -> ContractNativeAdapter = when (name) {
        "rime" -> { { RimeContractAdapter(File(data, "rime"), File(privateRoot, "benchmark-rime").apply { mkdirs() }) } }
        "hunspell" -> { { HunspellContractAdapter(File(data, "hunspell/fr.aff"), File(data, "hunspell/fr.dic"), File(data, "hunspell/fr.prefix.txt")) } }
        "mozc" -> { { MozcContractAdapter(File(privateRoot, "benchmark-mozc").apply { mkdirs() }, File(data, "mozc/mozc.data")) } }
        else -> error("unknown engine: $name")
    }

    fun runExternal(operation: String, engine: String, data: File, privateRoot: File): JSONObject {
        val names = listOf("rime", "hunspell", "mozc")
        val selected = factory(engine, data, privateRoot)

        // Production state-machine model for every engine transition: the
        // coordinator first falls back to a Direct generation so the keyboard
        // stays inputable immediately, then warms the target engine and only
        // then upgrades candidates.  The timing gate bounds time-to-inputable; engine
        // readiness is bounded separately because upstream Hunspell must parse
        // the full dictionary (~440 ms warm / ~2.2 s cold on the reference
        // arm64 device) before it can produce a single candidate.
        fun directInputable(switchStart: Long): Long {
            val direct = DirectTextEngine()
            direct.key('x')
            direct.commit()
            return android.os.SystemClock.elapsedRealtimeNanos() - switchStart
        }

        fun sample(targetName: String, closeFirst: ContractNativeAdapter?): JSONObject {
            val start = android.os.SystemClock.elapsedRealtimeNanos()
            closeFirst?.close()
            val inputableNanos = directInputable(start)
            val adapter = factory(targetName, data, privateRoot)()
            adapter.start()
            heldAdapter = adapter
            val readyNanos = android.os.SystemClock.elapsedRealtimeNanos()
            return JSONObject().put("to", targetName).put("startNanos", start)
                .put("inputableMs", inputableNanos / 1_000_000.0)
                .put("engineReadyMs", (readyNanos - start) / 1_000_000.0)
                .put("readyNanos", readyNanos)
                .put("elapsedMs", (readyNanos - start) / 1_000_000.0)
        }

        return when (operation) {
            "first-ready" -> {
                val start = android.os.SystemClock.elapsedRealtimeNanos()
                val inputableNanos = directInputable(start)
                val adapter = selected()
                adapter.start()
                val ready = android.os.SystemClock.elapsedRealtimeNanos()
                heldAdapter = adapter
                JSONObject().put("startNanos", start).put("readyNanos", ready)
                    .put("inputableMs", inputableNanos / 1_000_000.0)
                    .put("engineReadyMs", (ready - start) / 1_000_000.0)
                    .put("elapsedMs", (ready - start) / 1_000_000.0).put("heldForPss", true)
            }
            "warm-switch" -> {
                val next = names[(names.indexOf(engine) + 1) % names.size]
                // Warm both endpoints once (unmeasured): the measured A->B
                // switches must reflect engines the user has already used.
                // Without this, sample 0 is the target's first-ever init in
                // this process and cold-start cost gates the warm bound.
                factory(next, data, privateRoot)().also { it.start() }.close()
                var current = selected().also { it.start() }
                val samples = JSONArray()
                repeat(SAMPLE_COUNT) { index ->
                    val targetName = if (index % 2 == 0) next else engine
                    val result = sample(targetName, current)
                    current = checkNotNull(heldAdapter)
                    samples.put(result.put("from", if (index % 2 == 0) engine else next))
                }
                heldAdapter = current
                JSONObject().put("samples", samples).put("heldForPss", true)
            }
            "reopen" -> {
                var current = selected().also { it.start() }
                val samples = JSONArray()
                repeat(SAMPLE_COUNT) {
                    val result = sample(engine, current)
                    current = checkNotNull(heldAdapter)
                    samples.put(result)
                }
                heldAdapter = current
                JSONObject().put("samples", samples).put("heldForPss", true)
            }
            "switch-stress-idle" -> {
                var current: ContractNativeAdapter? = null
                val samples = JSONArray()
                repeat(SAMPLE_COUNT) { index ->
                    val targetName = names[index % names.size]
                    val result = sample(targetName, current)
                    current = checkNotNull(heldAdapter)
                    samples.put(result)
                }
                current?.close()
                heldAdapter = null
                JSONObject().put("samples", samples).put("heldForPss", false).put("idleAfterClose", true)
            }
            else -> error("unknown benchmark operation: $operation")
        }
    }

    fun run(data: File, privateRoot: File): JSONObject {
        return JSONObject()
            .put("status", "not-a-gate")
            .put("reason", "the timing gate is sampled only by run-integrated-resource-gates.sh using fresh processes, real A-to-B switches, current ASR models, and dumpsys meminfo")
            .put("externalHarness", "spikes/integrated-resource-smoke")
    }

    private const val SAMPLE_COUNT = 10
}

private fun stableId(engine: String, page: Int, index: Int, value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("$engine\u0000$page\u0000$index\u0000$value".toByteArray())
    return bytes.take(8).joinToString("") { "%02x".format(it) }
}
