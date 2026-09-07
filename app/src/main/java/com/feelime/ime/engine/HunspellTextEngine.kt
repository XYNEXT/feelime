package com.feelime.ime.engine

import android.content.Context
import com.feelime.ime.nativeengine.NativeSmoke
import com.feelime.ime.nativeengine.PrefixIndex
import java.io.File

/**
 * Hunspell adapter for French/Russian: a composing buffer with offline
 * suggestions (dictionary + prefix index). The upstream constructor never
 * throws on bad data, so Start proves the dictionary with a known probe word
 * before reporting READY (G1 lesson).
 */
class HunspellTextEngine(
    context: Context,
    private val locale: String,
    private val probeWord: String,
) : NativeTextEngine() {
    private val appContext = context.applicationContext
    private var handle = 0L
    private var prefixIndex: PrefixIndex? = null
    private var composing = ""
    private var allCandidates: List<Candidate> = emptyList()
    private var page = 0

    override fun startNative() {
        val dataRoot = EngineDataStore.verifyGroup(appContext, "hunspell")
            ?: throw EngineFailure(
                if (EngineDataStore.mismatched()) EngineCode.ENGINE_DATA_MISMATCH
                else EngineCode.ENGINE_INIT_FAILED,
            )
        val dir = File(dataRoot, "hunspell")
        synchronized(gate) {
            handle = NativeSmoke.hunspellCreate(File(dir, "$locale.aff").path, File(dir, "$locale.dic").path)
            if (handle == 0L) throw EngineFailure(EngineCode.ENGINE_INIT_FAILED)
            if (NativeSmoke.hunspellSpell(handle, probeWord) != 1) {
                throw EngineFailure(EngineCode.ENGINE_INIT_FAILED)
            }
            prefixIndex = PrefixIndex.load(File(dir, "$locale.prefix.txt"))
        }
    }

    override fun handle(request: EngineRequest, emit: (EngineEvent) -> Unit): DispatchAck {
        when (val command = request.command) {
            is EngineCommand.Key -> {
                composing += String(intArrayOf(command.unicodeScalar), 0, 1)
                rebuild()
                emit(event(Phase.READY, viewState()))
            }
            is EngineCommand.Space -> {
                val commit = composing + " "
                composing = ""
                rebuild()
                emit(event(Phase.READY, viewState(commit = commit)))
            }
            is EngineCommand.EnterRaw -> {
                val commit = composing
                composing = ""
                rebuild()
                emit(event(Phase.READY, viewState(commit = commit)))
            }
            is EngineCommand.Backspace -> {
                if (composing.isEmpty()) {
                    emit(event(Phase.READY, viewState(), consumed = false, code = EngineCode.EMPTY_COMPOSING))
                } else {
                    // delete a full code point, never a surrogate half.
                    val end = composing.length
                    val start = end - Character.charCount(composing.codePointBefore(end))
                    composing = composing.substring(0, start)
                    rebuild()
                    emit(event(Phase.READY, viewState()))
                }
            }
            is EngineCommand.Choose -> {
                val chosen = allCandidates.firstOrNull { it.id == command.candidateId }
                if (chosen == null || command.expectedRevision != currentRevision()) {
                    return staleRevision()
                }
                composing = ""
                rebuild()
                // A picked word lands WITH a trailing space. Without it every
                // confirmed word glued to
                // the next one (bonjourmonde), because the JS space key goes
                // through the shared pool-top pick instead of the
                // Space command, and Choose used to commit the bare word.
                // Same "word + space" shape as Space below and
                // DirectTextEngine.Space; Russian shares this engine.
                emit(event(Phase.READY, viewState(commit = chosen.text + " ")))
            }
            is EngineCommand.PageNext -> {
                // stale paging must never move the page.
                if (command.expectedRevision != currentRevision()) return staleRevision()
                val maxPage = ((allCandidates.size - 1).coerceAtLeast(0)) / PAGE_SIZE
                if (page >= maxPage) {
                    emit(event(Phase.READY, viewState(), consumed = false, code = EngineCode.PAGE_BOUNDARY))
                } else {
                    page += 1
                    emit(event(Phase.READY, viewState()))
                }
            }
            is EngineCommand.PagePrevious -> {
                if (command.expectedRevision != currentRevision()) return staleRevision()
                if (page == 0) {
                    emit(event(Phase.READY, viewState(), consumed = false, code = EngineCode.PAGE_BOUNDARY))
                } else {
                    page -= 1
                    emit(event(Phase.READY, viewState()))
                }
            }
            is EngineCommand.Reset -> {
                composing = ""
                rebuild()
                emit(event(Phase.READY, viewState()))
            }
            // No user-dict candidates to delete here.
            is EngineCommand.DeleteHighlighted -> DispatchAck.Rejected(EngineCode.NOT_DELETABLE)
            is EngineCommand.DeleteCandidate -> DispatchAck.Rejected(EngineCode.NOT_DELETABLE)
            EngineCommand.Close -> close(emit)
            EngineCommand.Start -> return DispatchAck.Rejected(EngineCode.STALE_STAMP)
        }
        return DispatchAck.Accepted
    }

    private fun rebuild() {
        page = 0
        if (composing.isEmpty()) {
            allCandidates = emptyList()
            return
        }
        val suggested = NativeSmoke.hunspellSuggest(handle, composing)
            .lineSequence().filter { it.isNotEmpty() }.toMutableList()
        suggested += prefixIndex?.find(composing, PAGE_SIZE * 3).orEmpty()
        if (NativeSmoke.hunspellSpell(handle, composing) == 1) suggested.add(0, composing)
        allCandidates = suggested.distinct().mapIndexed { index, value ->
            Candidate(stableCandidateId("hunspell-$locale", 0, index, value), value)
        }
    }

    private fun viewState(commit: String? = null): EngineState = EngineState(
        composing = composing,
        rawInput = composing,
        candidates = allCandidates.drop(page * PAGE_SIZE).take(PAGE_SIZE),
        hasPreviousPage = page > 0,
        hasNextPage = allCandidates.size > (page + 1) * PAGE_SIZE,
        commit = commit,
    )

    override fun closeNative() {
        synchronized(gate) {
            if (handle != 0L) NativeSmoke.hunspellDestroy(handle)
            handle = 0
        }
    }

    companion object {
        private const val PAGE_SIZE = 8
        private val gate = Any()
    }
}
