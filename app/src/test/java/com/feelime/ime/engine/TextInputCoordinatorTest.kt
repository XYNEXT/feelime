package com.feelime.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEditor : EditorPort {
    val operations = mutableListOf<String>()
    var selection: String? = null
    var text = ""
    var caret = 0
    private var composingStart: Int? = null
    private var composingEnd = 0

    private fun replace(value: String, composing: Boolean) {
        val start = composingStart ?: caret
        val end = if (composingStart != null) composingEnd else caret
        text = text.substring(0, start) + value + text.substring(end)
        caret = start + value.length
        composingStart = if (composing) start else null
        composingEnd = caret
    }

    override fun reopenComposing(start: Int, end: Int, word: String): Boolean {
        if (start !in 0..text.length || end !in start..text.length) return false
        operations.add("reopenComposing:$word")
        composingStart = start
        composingEnd = end
        replace(word, true)
        return true
    }
    var codePointDeleteSucceeds = true

    override fun finishComposing() {
        composingStart = null
        operations.add("finishComposing")
    }

    override fun setComposing(text: String) {
        replace(text, true)
        operations.add("setComposing:$text")
    }

    override fun commitText(text: String) {
        replace(text, false)
        operations.add("commitText:$text")
    }

    override fun selectedText(): String? = selection

    override fun deleteSurroundingCodePoints(count: Int): Boolean {
        operations.add("deleteCodePoints:$count")
        return codePointDeleteSucceeds
    }

    override fun sendDeleteKey() {
        if (caret > 0) { text = text.removeRange(caret - 1, caret); caret -= 1 }
        operations.add("sendDeleteKey")
    }

    override fun performEditorAction() {
        operations.add("performEditorAction")
    }
}

/** Scriptable engine used to drive the coordinator's stamp/revision logic. */
class FakeEngine : TextEngine {
    var stamp: EngineStamp? = null
    var revision = 0L
    var composing = ""
    val requests = mutableListOf<EngineRequest>()
    var onStart: (EngineRequest, (EngineEvent) -> Unit) -> Unit = { request, emit ->
        emit(state(request.stamp, Phase.LOADING))
        emit(state(request.stamp, Phase.READY))
    }

    override fun dispatch(request: EngineRequest, emit: (EngineEvent) -> Unit): DispatchAck {
        requests.add(request)
        val bound = stamp
        if (request.command is EngineCommand.Start) {
            stamp = request.stamp
            revision = 0
            composing = ""
            onStart(request, emit)
            return DispatchAck.Accepted
        }
        if (bound == null || request.stamp != bound) return DispatchAck.Rejected(EngineCode.STALE_STAMP)
        when (val command = request.command) {
            is EngineCommand.Key -> {
                composing += command.unicodeScalar.toChar()
                emit(state(request.stamp, Phase.READY, consumed = true, composing = composing))
            }
            is EngineCommand.Backspace -> {
                if (composing.isEmpty()) {
                    emit(state(request.stamp, Phase.READY, code = EngineCode.EMPTY_COMPOSING, consumed = false))
                } else {
                    composing = composing.dropLast(1)
                    emit(state(request.stamp, Phase.READY, consumed = true, composing = composing))
                }
            }
            is EngineCommand.Space -> {
                val commit = if (composing.isEmpty()) " " else "$composing "
                composing = ""
                emit(state(request.stamp, Phase.READY, consumed = true, commit = commit))
            }
            is EngineCommand.Choose -> {
                val commit = "$composing "
                composing = ""
                emit(state(request.stamp, Phase.READY, consumed = true, commit = commit))
            }
            is EngineCommand.PageNext -> {
                if (command.expectedRevision != revision) {
                    return DispatchAck.Rejected(EngineCode.STALE_REVISION)
                }
                emit(state(request.stamp, Phase.READY, code = EngineCode.PAGE_BOUNDARY, consumed = false))
            }
            is EngineCommand.PagePrevious -> {
                if (command.expectedRevision != revision) {
                    return DispatchAck.Rejected(EngineCode.STALE_REVISION)
                }
                emit(state(request.stamp, Phase.READY, code = EngineCode.PAGE_BOUNDARY, consumed = false))
            }
            is EngineCommand.Close -> {
                emit(EngineEvent(request.stamp, ++revision, Phase.CLOSED, emptyState(), true, EngineCode.ENGINE_CLOSED))
                stamp = null
            }
            is EngineCommand.EnterRaw -> {
                val raw = composing
                composing = ""
                emit(state(request.stamp, Phase.READY, consumed = true, commit = raw.ifEmpty { null }))
            }
            is EngineCommand.Reset -> {
                composing = ""
                emit(state(request.stamp, Phase.READY, consumed = true))
            }
            else -> Unit
        }
        return DispatchAck.Accepted
    }

    fun state(
        stamp: EngineStamp,
        phase: Phase,
        code: EngineCode = EngineCode.OK,
        consumed: Boolean = true,
        commit: String? = null,
        composing: String = "",
    ): EngineEvent {
        revision += 1
        return EngineEvent(
            stamp, revision, phase,
            EngineState(composing, composing, emptyList(), false, false, commit),
            consumed, code,
        )
    }

    fun emptyState() = EngineState("", "", emptyList(), false, false, null)
}

class TextInputCoordinatorTest {
    private lateinit var editor: RecordingEditor
    private lateinit var events: MutableList<EngineEvent>
    private lateinit var fake: FakeEngine
    private var asrGuardCalls = 0

    private fun coordinator(): TextInputCoordinator {
        editor = RecordingEditor()
        events = mutableListOf()
        fake = FakeEngine()
        return TextInputCoordinator(
            editor = editor,
            listener = { events.add(it) },
            engineFactory = { fake },
            asrGuard = { asrGuardCalls += 1 },
        )
    }

    private fun revisions() = events.map { it.revision }

    @Test
    fun startEmitsLoadingThenReadyWithStrictRevisions() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        assertEquals(listOf(Phase.LOADING, Phase.READY), events.map { it.phase })
        assertEquals(listOf(1L, 2L), revisions())
        assertTrue(revisions().zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun stampMismatchedEventsAreDropped() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        val stale = fake.state(
            EngineStamp(0, 0, InputMode.DIRECT),
            Phase.READY,
        )
        val before = events.size
        c.onEngineEvent(stale)
        assertEquals(before, events.size)
    }

    @Test
    fun outOfOrderRevisionsAreDropped() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        val currentStamp = fake.stamp ?: error("no stamp")
        val before = events.size
        c.onEngineEvent(
            EngineEvent(currentStamp, 1, Phase.READY, events.last().state, true, EngineCode.OK),
        )
        assertEquals(before, events.size)
    }

    @Test
    fun initFailureFallsBackToDirectInNewGeneration() {
        val c = coordinator()
        fake.onStart = { request, emit ->
            emit(EngineEvent(request.stamp, 1, Phase.ERROR, emptyFailureState(), false, EngineCode.ENGINE_INIT_FAILED))
        }
        c.onEditorStarted(sensitive = false)
        // The ERROR is surfaced, then a fresh DIRECT session starts.
        assertEquals(EngineCode.ENGINE_INIT_FAILED, events[0].code)
        assertEquals(Phase.LOADING, events[1].phase)
        assertEquals(InputMode.DIRECT, events[1].stamp.mode)
        assertTrue(c.key('x'.code) == DispatchAck.Accepted)
        assertEquals("x", events.last().state.commit)
        assertTrue(editor.operations.contains("commitText:x"))
    }

    private fun emptyFailureState() = EngineState("", "", emptyList(), false, false, null)

    @Test
    fun closeBumpsGenerationAndDropsLateEvents() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        val sessionStamp = fake.stamp ?: error("no stamp")
        c.close()
        assertEquals(EngineCode.ENGINE_CLOSED, events.last().code)
        val before = events.size
        c.onEngineEvent(
            EngineEvent(sessionStamp, 99, Phase.READY, emptyFailureState(), true, EngineCode.OK),
        )
        assertEquals(before, events.size)
    }

    @Test
    fun emptyBackspaceTriggersEditorDeletionCascade() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.backspace()
        assertEquals(EngineCode.EMPTY_COMPOSING, events.last().code)
        assertFalse(events.last().consumed)
        // Committed text deletes via a real Backspace key event -
        // deleteSurroundingText never reached xterm.js-style hosts.
        assertEquals("sendDeleteKey", editor.operations.last())
    }

    @Test
    fun consumedBackspaceClearsLastComposingCharacterBeforeFinishing() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.key('n'.code)
        val before = editor.operations.size

        c.backspace()

        val operations = editor.operations.drop(before)
        assertTrue(operations.contains("setComposing:"))
        assertTrue(operations.contains("finishComposing"))
        assertFalse(operations.any { it == "commitText:n" })
    }

    @Test
    fun selectionDeletionGoesThroughTheKeyChannel() {
        // The selectedText pre-check is gone (its sync IC
        // call burned a 2000ms watchdog per key against the in-process
        // settings WebView). KEYCODE_DEL natively removes a selection, so a
        // selection present must NOT divert the cascade into commitText.
        val c = coordinator()
        editor.selection = "selected text"
        c.onEditorStarted(sensitive = false)
        c.backspace()
        assertFalse(editor.operations.contains("commitText:"))
        assertFalse(editor.operations.contains("deleteCodePoints:1"))
        assertEquals("sendDeleteKey", editor.operations.last())
    }

    @Test
    fun committedDeleteNeverTouchesSurroundingText() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.backspace()
        assertFalse(editor.operations.contains("deleteCodePoints:1"))
        assertEquals("sendDeleteKey", editor.operations.last())
    }

    @Test
    fun passwordFieldScrubsToNoLearningDirect() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.key('a'.code)
        val beforeOps = editor.operations.size
        c.enterPasswordField()
        assertTrue(asrGuardCalls >= 1)
        assertTrue(editor.operations.drop(beforeOps).contains("setComposing:"))
        assertTrue(editor.operations.drop(beforeOps).contains("finishComposing"))
        assertEquals(InputMode.DIRECT, events.last().stamp.mode)
        // Typing still works and never leaves the direct engine.
        c.key('p'.code)
        assertEquals("p", events.last().state.composing)
    }

    @Test
    fun leavingPasswordRestoresSavedModeInNewGeneration() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.JAPANESE)
        val japaneseStamp = events.last().stamp
        c.enterPasswordField()
        c.leavePasswordField()
        assertEquals(InputMode.JAPANESE, events.last().stamp.mode)
        assertFalse(events.last().stamp.engineSessionGeneration <= japaneseStamp.engineSessionGeneration)
    }

    @Test
    fun modeSwitchCommitsComposingFirstAndDropsOldCallbacks() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.key('a'.code)
        val oldStamp = fake.stamp
        c.selectMode(InputMode.JAPANESE)
        assertTrue(editor.operations.contains("finishComposing"))
        val before = events.size
        c.onEngineEvent(
            EngineEvent(oldStamp!!, 42, Phase.READY, emptyFailureState(), true, EngineCode.OK),
        )
        assertEquals(before, events.size)
    }

    @Test
    fun editorRestartBumpsGenerationsAndGuardsAsr() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        val firstStamp = events.last().stamp
        c.onEditorStarted(sensitive = false)
        val secondStamp = events.last().stamp
        assertTrue(secondStamp.editorGeneration > firstStamp.editorGeneration)
        assertTrue(secondStamp.engineSessionGeneration > firstStamp.engineSessionGeneration)
        assertTrue(asrGuardCalls >= 2)
    }

    @Test
    fun sensitiveEditorStartsDirectWithoutTouchingSavedMode() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.PINYIN)
        val beforeSensitive = editor.operations.size
        c.onEditorStarted(sensitive = true)
        assertEquals(InputMode.DIRECT, events.last().stamp.mode)
        val scrub = editor.operations.drop(beforeSensitive)
        assertTrue(scrub.contains("setComposing:"))
        assertTrue(scrub.contains("finishComposing"))
        c.onEditorStarted(sensitive = false)
        assertEquals(InputMode.PINYIN, events.last().stamp.mode)
    }

    @Test
    fun voiceSessionUsesItsOwnGeneration() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.key('a'.code)
        val textStamp = fake.stamp
        c.beginVoiceSession()
        assertTrue(editor.operations.contains("finishComposing"))
        val before = events.size
        c.onEngineEvent(
            EngineEvent(textStamp!!, 77, Phase.READY, emptyFailureState(), true, EngineCode.OK),
        )
        assertEquals(before, events.size)
        c.endVoiceSession()
        assertEquals(Phase.READY, events.last().phase)
        assertTrue(c.key('b'.code) == DispatchAck.Accepted)
    }

    @Test
    fun slowEngineStartsOnPendingStampAndReplaysQueuedKeys() {
        events = mutableListOf()
        val queue = mutableListOf<Runnable>()
        val executor = java.util.concurrent.Executor { queue.add(it) }
        val slow = FakeEngine()
        val c = TextInputCoordinator(
            editor = RecordingEditor().also { editor = it },
            listener = { events.add(it) },
            engineFactory = { mode ->
                if (mode == InputMode.JAPANESE) slow else DirectTextEngine()
            },
            asrGuard = {},
            background = executor,
        )
        c.onEditorStarted(sensitive = false)
        val liveStamp = c.currentStamp
        c.selectMode(InputMode.JAPANESE)
        // the key is accepted and queued during warmup — the interim
        // engine does NOT commit it as literal text.
        assertTrue(c.key('x'.code) == DispatchAck.Accepted)
        assertFalse(editor.operations.any { it.startsWith("commitText") })
        assertEquals(InputMode.JAPANESE, c.currentStamp.mode)
        assertTrue(c.currentStamp.engineSessionGeneration > liveStamp.engineSessionGeneration)
        queue.forEach { it.run() }
        assertTrue(c.key('a'.code) == DispatchAck.Accepted)
        // After the upgrade the key went to the slow engine's stamp.
        assertEquals(slow.stamp, events.last().stamp)
    }

    @Test
    fun pendingEngineFailureFallsBackToDirectTyping() {
        events = mutableListOf()
        val queue = mutableListOf<Runnable>()
        val executor = java.util.concurrent.Executor { queue.add(it) }
        val slow = FakeEngine()
        slow.onStart = { request, emit ->
            emit(EngineEvent(request.stamp, 1, Phase.ERROR, emptyFailureState(), false, EngineCode.ENGINE_INIT_FAILED))
        }
        val c = TextInputCoordinator(
            editor = RecordingEditor().also { editor = it },
            listener = { events.add(it) },
            engineFactory = { mode ->
                if (mode == InputMode.JAPANESE) slow else DirectTextEngine()
            },
            background = executor,
        )
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.JAPANESE)
        // Drain until empty: the failure path schedules a background Close on
        // the same executor, so a single forEach would throw CME.
        while (queue.isNotEmpty()) queue.removeAt(0).run()
        assertTrue(events.any { it.code == EngineCode.ENGINE_INIT_FAILED })
        assertTrue(c.key('z'.code) == DispatchAck.Accepted)
        assertEquals("z", events.last().state.commit)
    }

    @Test
    fun latePendingEventsAfterRestartAreDropped() {
        events = mutableListOf()
        val queue = mutableListOf<Runnable>()
        val executor = java.util.concurrent.Executor { queue.add(it) }
        val slow = FakeEngine()
        val c = TextInputCoordinator(
            editor = RecordingEditor().also { editor = it },
            listener = { events.add(it) },
            engineFactory = { mode ->
                if (mode == InputMode.JAPANESE) slow else DirectTextEngine()
            },
            background = executor,
        )
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.JAPANESE)
        queue.forEach { it.run() }
        val pendingStamp = slow.stamp!!
        c.onEditorStarted(sensitive = false)
        val before = events.size
        c.onEngineEvent(
            EngineEvent(pendingStamp, 5, Phase.READY, emptyFailureState(), true, EngineCode.OK),
        )
        assertEquals(before, events.size)
    }

    private class ManualExecutor : java.util.concurrent.Executor {
        val tasks = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun runAll() {
            val pending = tasks.toList()
            tasks.clear()
            pending.forEach { it.run() }
        }
    }

    private fun mapModeStore(store: MutableMap<String, InputMode>) =
        object : TextInputCoordinator.ModeStore {
            override fun save(mode: InputMode) {
                store["mode"] = mode
            }

            override fun load(): InputMode? = store["mode"]
        }

    @Test
    fun g2B04ModeSurvivesCoordinatorRecreation() {
        val store = mutableMapOf<String, InputMode>()
        val c1 = TextInputCoordinator(
            editor = RecordingEditor(),
            listener = { },
            engineFactory = { FakeEngine() },
            modeStore = mapModeStore(store),
        )
        c1.onEditorStarted(sensitive = false)
        c1.selectMode(InputMode.PINYIN)
        assertEquals(InputMode.PINYIN, store["mode"])

        // Process death: a brand-new coordinator must restore PINYIN.
        val fake2 = FakeEngine()
        val c2 = TextInputCoordinator(
            editor = RecordingEditor(),
            listener = { },
            engineFactory = { fake2 },
            modeStore = mapModeStore(store),
        )
        c2.onEditorStarted(sensitive = false)
        assertEquals(InputMode.PINYIN, fake2.stamp?.mode)
    }

    @Test
    fun g2B04PasswordFieldDoesNotOverwriteSavedMode() {
        val store = mutableMapOf<String, InputMode>()
        val c = TextInputCoordinator(
            editor = RecordingEditor(),
            listener = { },
            engineFactory = { FakeEngine() },
            modeStore = mapModeStore(store),
        )
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.FRENCH)
        c.enterPasswordField()
        assertEquals(InputMode.DIRECT, c.currentMode)
        assertEquals(InputMode.FRENCH, store["mode"])
        // Mode selection inside a password field is refused outright.
        assertEquals(
            TextInputCoordinator.ModeSelectionResult.BLOCKED_SENSITIVE_EDITOR,
            c.selectMode(InputMode.RUSSIAN),
        )
        assertEquals(InputMode.DIRECT, c.currentMode)
        assertEquals(InputMode.FRENCH, store["mode"])
    }

    @Test
    fun terminalEditorKeepsSavedModeAndAcceptsSwitches() {
        val store = mutableMapOf<String, InputMode>()
        val c = TextInputCoordinator(
            editor = RecordingEditor(),
            listener = { },
            engineFactory = { FakeEngine() },
            modeStore = mapModeStore(store),
        )
        c.onEditorStarted(sensitive = false)
        assertEquals(TextInputCoordinator.ModeSelectionResult.ACCEPTED, c.selectMode(InputMode.PINYIN))
        assertEquals(InputMode.PINYIN, store["mode"])

        // Terminal (TYPE_NULL) editors no longer force Direct - they
        // open with the user's saved mode and accept every switch request.
        c.onEditorStarted(sensitive = false, terminalLike = true)
        assertEquals(InputMode.PINYIN, c.currentMode)
        assertEquals(
            TextInputCoordinator.ModeSelectionResult.ACCEPTED,
            c.selectMode(InputMode.FRENCH),
        )
        assertEquals(InputMode.FRENCH, c.currentMode)
        assertEquals(InputMode.FRENCH, store["mode"])

        // A subsequent real text editor keeps the saved mode; its switch
        // requests are accepted as usual.
        c.onEditorStarted(sensitive = false, terminalLike = false)
        assertEquals(InputMode.FRENCH, c.currentMode)
        assertEquals(
            TextInputCoordinator.ModeSelectionResult.ACCEPTED,
            c.selectMode(InputMode.PINYIN),
        )
        assertEquals(InputMode.PINYIN, c.currentMode)
    }

    @Test
    fun g2B05WarmupQueuesKeysAndNeverReportsReadyEarly() {
        editor = RecordingEditor()
        events = mutableListOf()
        val slow = FakeEngine()
        val executor = ManualExecutor()
        val c = TextInputCoordinator(
            editor = editor,
            listener = { events.add(it) },
            engineFactory = { if (it == InputMode.PINYIN) slow else DirectTextEngine() },
            background = executor,
        )
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.PINYIN)
        // Keys typed during warmup are accepted...
        assertEquals(DispatchAck.Accepted, c.key('n'.code))
        assertEquals(DispatchAck.Accepted, c.key('i'.code))
        // ...but no READY was claimed for the pinyin session and NOTHING hit
        // the editor yet.
        assertTrue(events.none { it.phase == Phase.READY && it.stamp.mode == InputMode.PINYIN })
        assertEquals(Phase.LOADING, events.last().phase)
        assertTrue(editor.operations.none { it.startsWith("commitText") || it.startsWith("setComposing") })
        // The pending engine is untouched until the background start runs.
        assertEquals(0, slow.requests.size)

        executor.runAll()
        // READY arrived and the queued keys replayed as composing — never as
        // committed latin text, so no word is split across engines. 
        // the pinyin preedit surfaces on the keyboard UI (echo state), not in
        // the editor (#8).
        assertTrue(events.any { it.phase == Phase.READY })
        assertEquals("ni", events.last().state.composing)
        assertFalse(editor.operations.contains("setComposing:ni"))
        assertFalse(editor.operations.contains("commitText:n"))
        assertFalse(editor.operations.contains("commitText:ni"))
    }

    @Test
    fun g2B05WarmupFailureReplaysQueueIntoDirectFallback() {
        editor = RecordingEditor()
        events = mutableListOf()
        val slow = FakeEngine().apply {
            onStart = { request, emit ->
                emit(
                    EngineEvent(
                        request.stamp, 1, Phase.ERROR, emptyFailureState(),
                        false, EngineCode.ENGINE_INIT_FAILED,
                    ),
                )
            }
        }
        val executor = ManualExecutor()
        val c = TextInputCoordinator(
            editor = editor,
            listener = { events.add(it) },
            engineFactory = { if (it == InputMode.PINYIN) slow else DirectTextEngine() },
            background = executor,
        )
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.PINYIN)
        c.key('n'.code)
        executor.runAll()
        // Fallback Direct committed the queued key literally and the
        // keyboard is still fully usable.
        assertTrue(editor.operations.contains("commitText:n"))
        assertEquals(DispatchAck.Accepted, c.key('o'.code))
        assertTrue(editor.operations.contains("commitText:o"))
    }

    @Test
    fun modeSwitchDuringWarmupReplaysEveryQueuedCommandBeforeLanding() {
        editor = RecordingEditor()
        events = mutableListOf()
        val slow = FakeEngine()
        val executor = ManualExecutor()
        val c = TextInputCoordinator(
            editor = editor,
            listener = { events.add(it) },
            engineFactory = { if (it == InputMode.PINYIN) slow else DirectTextEngine() },
            background = executor,
        )
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.PINYIN)

        // Keep every command type in the warmup sequence.  The final key
        // leaves an active pinyin raw buffer so the deferred mode switch also
        // exercises the shared landing path.
        c.key('n'.code)
        c.key('i'.code)
        c.backspace()
        c.space()
        c.key('h'.code)
        c.enterRaw()
        c.key('x'.code)
        c.selectMode(InputMode.DIRECT)

        // The requested switch is deferred; no accepted command disappears
        // while the pinyin engine is still warming up.
        assertEquals(InputMode.PINYIN, c.currentMode)
        assertTrue(editor.operations.none { it.startsWith("commitText") })
        executor.runAll()

        assertEquals(InputMode.DIRECT, c.currentMode)
        assertTrue(editor.operations.contains("commitText:n "))
        assertTrue(editor.operations.contains("commitText:h"))
        assertTrue(editor.operations.contains("commitText:x"))
        assertEquals(1, editor.operations.count { it == "commitText:x" })
        // Enter consumed the queued composition instead of firing a host
        // action before the preceding queued keys had been applied.
        assertFalse(editor.operations.contains("performEditorAction"))
    }

    @Test
    fun explicitCompositionAcceptanceWaitsForWarmupBeforeCompletion() {
        editor = RecordingEditor()
        events = mutableListOf()
        val executor = ManualExecutor()
        val c = TextInputCoordinator(
            editor = editor,
            listener = { events.add(it) },
            engineFactory = { if (it == InputMode.PINYIN) FakeEngine() else DirectTextEngine() },
            background = executor,
        )
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.PINYIN)
        c.key('n'.code)

        var completed = false
        c.acceptCurrentComposition { completed = true }
        assertFalse(completed)
        executor.runAll()

        assertTrue(completed)
        assertEquals(1, editor.operations.count { it == "commitText:n" })
    }

    @Test
    fun delayedPendingStartCannotAdoptANewerModesStamp() {
        editor = RecordingEditor()
        events = mutableListOf()
        val japanese = FakeEngine()
        val pinyin = FakeEngine()
        val executor = ManualExecutor()
        val c = TextInputCoordinator(
            editor = editor,
            listener = { events.add(it) },
            engineFactory = {
                when (it) {
                    InputMode.JAPANESE -> japanese
                    InputMode.PINYIN -> pinyin
                    else -> DirectTextEngine()
                }
            },
            background = executor,
        )
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.JAPANESE)
        c.selectMode(InputMode.PINYIN)
        // The second switch is deferred until the first target can replay its
        // already accepted queue; that replay schedules the next warmup task.
        while (executor.tasks.isNotEmpty()) executor.runAll()

        assertEquals(InputMode.PINYIN, c.currentMode)
        assertEquals(InputMode.JAPANESE, japanese.requests.first().stamp.mode)
        assertEquals(InputMode.PINYIN, pinyin.requests.first().stamp.mode)
        assertTrue(events.any { it.phase == Phase.READY && it.stamp.mode == InputMode.JAPANESE })
        assertTrue(events.any { it.phase == Phase.READY && it.stamp.mode == InputMode.PINYIN })
    }

    @Test
    fun passwordTransitionBeforePendingStartDropsOldReady() {
        editor = RecordingEditor()
        events = mutableListOf()
        val slow = FakeEngine()
        val executor = ManualExecutor()
        val c = TextInputCoordinator(
            editor = editor,
            listener = { events.add(it) },
            engineFactory = { if (it == InputMode.JAPANESE) slow else DirectTextEngine() },
            background = executor,
        )
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.JAPANESE)
        c.onEditorStarted(sensitive = true)
        executor.runAll()

        assertEquals(InputMode.DIRECT, c.currentMode)
        assertTrue(events.none { it.phase == Phase.READY && it.stamp.mode == InputMode.JAPANESE })
    }

    @Test
    fun warmupQueuedEnterWaitsAndSuppressesHostActionAfterComposingReplay() {
        editor = RecordingEditor()
        events = mutableListOf()
        val slow = FakeEngine()
        val executor = ManualExecutor()
        val c = TextInputCoordinator(
            editor = editor,
            listener = { events.add(it) },
            engineFactory = { if (it == InputMode.PINYIN) slow else DirectTextEngine() },
            background = executor,
        )
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.PINYIN)
        c.key('n'.code)
        c.key('i'.code)
        c.enterRaw()
        assertFalse(editor.operations.contains("performEditorAction"))

        executor.runAll()
        assertTrue(editor.operations.contains("commitText:ni"))
        assertFalse(editor.operations.contains("performEditorAction"))
    }

    @Test
    fun warmupQueuedEmptyEnterRunsHostActionOnlyAfterReplay() {
        editor = RecordingEditor()
        events = mutableListOf()
        val executor = ManualExecutor()
        val c = TextInputCoordinator(
            editor = editor,
            listener = { events.add(it) },
            engineFactory = { FakeEngine() },
            background = executor,
        )
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.PINYIN)
        c.enterRaw()
        assertFalse(editor.operations.contains("performEditorAction"))
        executor.runAll()
        assertTrue(editor.operations.contains("performEditorAction"))
    }

    @Test
    fun newKeysDuringPostedWarmupReplayKeepTheirOrder() {
        val host = RecordingEditor()
        val engine = FakeEngine()
        val executor = ManualExecutor()
        val posted = ArrayDeque<() -> Unit>()
        val c = TextInputCoordinator(host, {}, { if (it == InputMode.FRENCH) engine else DirectTextEngine() },
            mainPoster = { posted.addLast(it) }, background = executor)
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst()() }
        c.onEditorStarted(false)
        drain()
        c.selectMode(InputMode.FRENCH)
        c.key('a'.code)
        c.key('b'.code)
        executor.runAll()
        while (c.engineWarming) posted.removeFirst()()
        c.key('c'.code)
        drain()
        assertEquals("abc", host.text)
    }

    @Test
    fun postedReopenReplacesOldSpanBeforeLaterKeys() {
        val host = RecordingEditor()
        val posted = ArrayDeque<() -> Unit>()
        val c = TextInputCoordinator(host, {}, { FakeEngine() }, mainPoster = { posted.addLast(it) })
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst()() }
        c.onEditorStarted(false)
        drain()
        c.selectMode(InputMode.FRENCH)
        drain()
        c.key('b'.code)
        c.key('o'.code)
        c.space()
        drain()
        assertEquals("bo ", host.text)
        c.onEditorSelectionChanged(0, 0, 3, 3)
        c.backspace()
        c.key('n'.code)
        drain()
        assertEquals("bon", host.text)
        c.space()
        drain()
        assertEquals("bon ", host.text)
    }

    @Test
    fun modeSwitchDuringPostedReplayFollowsEarlierAcceptedKeys() {
        val host = RecordingEditor()
        val executor = ManualExecutor()
        val posted = ArrayDeque<() -> Unit>()
        val c = TextInputCoordinator(host, {}, { if (it == InputMode.FRENCH) FakeEngine() else DirectTextEngine() },
            mainPoster = { posted.addLast(it) }, background = executor)
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst()() }
        c.onEditorStarted(false)
        drain()
        c.selectMode(InputMode.FRENCH)
        c.key('a'.code)
        c.key('b'.code)
        executor.runAll()
        while (c.engineWarming) posted.removeFirst()()
        c.selectMode(InputMode.DIRECT)
        c.key('c'.code)
        drain()
        assertEquals("abc", host.text)
        assertEquals(InputMode.DIRECT, c.currentMode)
    }

    @Test
    fun livePostedKeysLandBeforeModeSwitchAcceptAndEnter() {
        for (action in listOf("mode", "accept", "enter", "paste")) {
            val host = RecordingEditor()
            val posted = ArrayDeque<() -> Unit>()
            val c = TextInputCoordinator(host, {}, { FakeEngine() }, mainPoster = { posted.addLast(it) })
            fun drain() { while (posted.isNotEmpty()) posted.removeFirst()() }
            c.onEditorStarted(false)
            drain()
            c.selectMode(InputMode.FRENCH)
            drain()
            c.key('a'.code)
            c.key('b'.code)
            when (action) {
                "mode" -> c.selectMode(InputMode.RUSSIAN)
                "accept" -> c.acceptCurrentComposition()
                "paste" -> c.pasteExternal("!")
                else -> c.enterRaw()
            }
            drain()
            assertEquals(action, if (action == "paste") "ab!" else "ab", host.text)
            assertFalse(host.operations.contains("performEditorAction"))
        }
    }

    @Test
    fun voiceStartWaitsForWarmupKeysAndTheirLanding() {
        val host = RecordingEditor()
        val executor = ManualExecutor()
        val posted = ArrayDeque<() -> Unit>()
        val c = TextInputCoordinator(host, {}, { FakeEngine() },
            mainPoster = { posted.addLast(it) }, background = executor)
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst()() }
        c.onEditorStarted(false)
        drain()
        c.selectMode(InputMode.FRENCH)
        c.key('a'.code)
        c.key('b'.code)
        var started = false
        c.beginVoiceSession { assertEquals("ab", host.text); started = true }
        assertFalse(started)
        executor.runAll()
        drain()
        assertTrue(started)
        assertEquals("ab", host.text)
    }

    @Test
    fun invalidatedVoiceStartLeavesWarmedEngineAvailable() {
        val host = RecordingEditor()
        val executor = ManualExecutor()
        val french = FakeEngine()
        val c = TextInputCoordinator(host, {}, { mode ->
            if (mode == InputMode.FRENCH) french else DirectTextEngine()
        }, background = executor)
        c.onEditorStarted(false)
        c.selectMode(InputMode.FRENCH)
        var inputViewCurrent = true
        var started = false
        c.beginVoiceSession(isCurrent = { inputViewCurrent }) { started = true }

        // Hiding the input view changes the service lifecycle without a new
        // coordinator editor. Its queued callback still reaches the guard.
        inputViewCurrent = false
        executor.runAll()

        assertFalse(started)
        c.key('x'.code)
        assertTrue(french.requests.any { it.command == EngineCommand.Key('x'.code, 0) })
        assertFalse(french.requests.any { it.command is EngineCommand.Close })
    }

    @Test
    fun delayedVoiceStartIsDroppedWhenEditorChangesAndNewEngineStaysClean() {
        val host = RecordingEditor()
        val executor = ManualExecutor()
        val oldEngine = FakeEngine()
        val newEngine = FakeEngine()
        var frenchStarts = 0
        val c = TextInputCoordinator(
            editor = host,
            listener = {},
            engineFactory = { mode ->
                if (mode == InputMode.FRENCH) {
                    if (frenchStarts++ == 0) oldEngine else newEngine
                } else {
                    DirectTextEngine()
                }
            },
            background = executor,
        )
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.FRENCH)
        c.key('a'.code)
        val oldEditorGeneration = c.currentStamp.editorGeneration
        var started = false
        c.beginVoiceSession(
            isCurrent = { c.currentStamp.editorGeneration == oldEditorGeneration },
        ) { started = true }

        // onEditorStarted clears the warmup queue. The old voice continuation
        // must not survive that transition and reset the replacement engine.
        c.onEditorStarted(sensitive = false)
        executor.runAll()

        assertFalse(started)
        c.key('x'.code)
        assertTrue(newEngine.requests.any { request ->
            request.command is EngineCommand.Key &&
                (request.command as EngineCommand.Key).unicodeScalar == 'x'.code
        })
        assertFalse(oldEngine.requests.any { it.command is EngineCommand.Reset })
        assertFalse(newEngine.requests.any { it.command is EngineCommand.Reset })
    }

    @Test
    fun postedPanelFieldSwitchesAndSaveKeepEachFieldsAcceptedText() {
        val first = RecordingEditor()
        val second = RecordingEditor()
        var target = first
        val port = object : EditorPort by first {
            override fun setComposing(text: String) = target.setComposing(text)
            override fun commitText(text: String) = target.commitText(text)
            override fun finishComposing() = target.finishComposing()
        }
        val posted = ArrayDeque<() -> Unit>()
        val c = TextInputCoordinator(port, {}, { FakeEngine() }, mainPoster = { posted.addLast(it) })
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst()() }
        c.onEditorStarted(false)
        drain()
        c.selectMode(InputMode.FRENCH)
        drain()
        c.key('a'.code)
        c.acceptCurrentComposition { target = second; c.onInputTargetSelection(0, 0) }
        c.key('b'.code)
        c.acceptCurrentComposition { target = first; c.onInputTargetSelection(1, 1) }
        c.key('c'.code)
        var saved = false
        c.acceptCurrentComposition {
            assertEquals("ac", first.text)
            assertEquals("b", second.text)
            saved = true
        }
        drain()
        assertTrue(saved)
    }

    @Test
    fun liveEngineErrorReleasesQueuedInputIntoDirectFallback() {
        val host = RecordingEditor()
        val posted = ArrayDeque<() -> Unit>()
        val fake = FakeEngine()
        val failing = TextEngine { request, emit ->
            if (request.command == EngineCommand.Key('a'.code, 0)) {
                emit(fake.state(request.stamp, Phase.ERROR, code = EngineCode.ENGINE_RUNTIME_FAILED))
                DispatchAck.Accepted
            } else fake.dispatch(request, emit)
        }
        val c = TextInputCoordinator(host, {}, { if (it == InputMode.FRENCH) failing else DirectTextEngine() },
            mainPoster = { posted.addLast(it) })
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst()() }
        c.onEditorStarted(false)
        drain()
        c.selectMode(InputMode.FRENCH)
        drain()
        c.key('a'.code)
        c.key('b'.code)
        drain()
        assertEquals(InputMode.DIRECT, c.currentMode)
        assertEquals("b", host.text)
        c.key('c'.code)
        drain()
        assertEquals("bc", host.text)
    }

    @Test
    fun queuedCandidateThenBackspaceReopensBeforeNextLetter() {
        val host = RecordingEditor()
        val posted = ArrayDeque<() -> Unit>()
        val c = TextInputCoordinator(host, {}, { FakeEngine() }, mainPoster = { posted.addLast(it) })
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst()() }
        c.onEditorStarted(false)
        drain()
        c.selectMode(InputMode.FRENCH)
        drain()
        "bo".forEach { c.key(it.code) }
        c.choose(0, "first")
        c.backspace()
        c.key('n'.code)
        drain()
        assertEquals("bon", host.text)
        c.backspace()
        drain()
        assertEquals("bo", host.text)
    }

    @Test
    fun candidateAutomaticSpaceJoinsCommaButPreservesFrenchWidePunctuation() {
        for ((punctuation, expected) in listOf(
            "," to "bonjour,", "." to "bonjour.", "!" to "bonjour !",
            ";" to "bonjour ;", ":" to "bonjour :", "?" to "bonjour ?",
        )) {
            val host = RecordingEditor()
            val posted = ArrayDeque<() -> Unit>()
            val c = TextInputCoordinator(host, {}, { FakeEngine() }, mainPoster = { posted.addLast(it) })
            fun drain() { while (posted.isNotEmpty()) posted.removeFirst()() }
            c.onEditorStarted(false)
            drain()
            c.selectMode(InputMode.FRENCH)
            drain()
            "bonjour".forEach { c.key(it.code) }
            c.choose(0, "first")
            c.key(punctuation.single().code)
            drain()
            assertEquals(expected, host.text)
        }
    }

    @Test
    fun symbolPanelCommaRemovesOnlyCandidateAutomaticSpace() {
        val c = coordinator()
        c.onEditorStarted(false)
        c.selectMode(InputMode.FRENCH)
        "bonjour".forEach { c.key(it.code) }
        c.choose(0, "first")
        c.pasteExternal(",")
        assertEquals("bonjour,", editor.text)
    }

    @Test
    fun manuallyTypedSpaceIsNeverRemovedBeforePunctuation() {
        val c = coordinator()
        c.onEditorStarted(false)
        c.selectMode(InputMode.FRENCH)
        "bonjour".forEach { c.key(it.code) }
        c.space()
        c.pasteExternal(",")
        assertEquals("bonjour ,", editor.text)
    }

    @Test
    fun apostropheAfterPickedFrenchWordProducesExactElision() {
        val c = coordinator()
        c.onEditorStarted(false)
        c.selectMode(InputMode.FRENCH)
        c.key('l'.code)
        c.choose(0, "first")
        c.key('’'.code)
        "été".forEach { c.key(it.code) }
        c.enterRaw()
        assertEquals("l’été", editor.text)
    }

    @Test
    fun apostrophesInsideActiveFrenchCompositionReachHunspellAsOneWord() {
        for (apostrophe in listOf('\'', '’')) {
            val host = RecordingEditor()
            val events = mutableListOf<EngineEvent>()
            val fake = FakeEngine()
            val c = TextInputCoordinator(
                editor = host,
                listener = { events.add(it) },
                engineFactory = { fake },
            )
            c.onEditorStarted(false)
            c.selectMode(InputMode.FRENCH)
            c.key('l'.code)

            val requestsBeforeApostrophe = fake.requests.size
            val operationsBeforeApostrophe = host.operations.size
            c.key(apostrophe.code)
            assertEquals(
                EngineCommand.Key(apostrophe.code, 0),
                fake.requests.drop(requestsBeforeApostrophe).single().command,
            )
            assertFalse(host.operations.drop(operationsBeforeApostrophe).any { it.startsWith("commitText:") })

            "homme".forEach { c.key(it.code) }
            val word = "l${apostrophe}homme"
            assertEquals(word, events.last().state.rawInput)
            assertEquals(word, events.last().state.composing)
            assertTrue(host.operations.contains("setComposing:$word"))

            c.enterRaw()
            assertEquals(word, host.text)
            assertEquals(1, host.operations.count { it == "commitText:$word" })
        }
    }

    @Test
    fun postedEnterFromPreviousEditorDoesNotRunNewEditorAction() {
        val host = RecordingEditor()
        val posted = ArrayDeque<() -> Unit>()
        val c = TextInputCoordinator(host, {}, { DirectTextEngine() }, mainPoster = { posted.addLast(it) })
        c.onEditorStarted(false)
        while (posted.isNotEmpty()) posted.removeFirst()()
        c.enterRaw()
        c.onEditorStarted(false)
        while (posted.isNotEmpty()) posted.removeFirst()()
        assertFalse(host.operations.contains("performEditorAction"))
    }

    @Test
    fun stalledWarmupTimeoutReplaysAcceptedKeysToDirect() {
        val host = RecordingEditor()
        val delays = mutableListOf<() -> Unit>()
        val c = TextInputCoordinator(host, {}, { FakeEngine() }, background = ManualExecutor(),
            delayPoster = { _, block -> delays.add(block) })
        c.onEditorStarted(false)
        c.selectMode(InputMode.FRENCH)
        c.key('a'.code)
        delays.single()()
        assertEquals("a", host.text)
        assertEquals(InputMode.DIRECT, c.currentMode)
    }

    @Test
    fun g2B06EditorTransitionsCloseEveryEngineSession() {
        val created = mutableListOf<FakeEngine>()
        editor = RecordingEditor()
        events = mutableListOf()
        val c = TextInputCoordinator(
            editor = editor,
            listener = { events.add(it) },
            engineFactory = { FakeEngine().also { created.add(it) } },
        )
        repeat(100) { index ->
            c.onEditorStarted(sensitive = index % 2 == 0)
        }
        assertEquals(100, created.size)
        // Every session except the live one was explicitly closed (create/close
        // balance); no native session leaks across editor transitions.
        assertTrue(created.dropLast(1).all { it.stamp == null })
        org.junit.Assert.assertNotNull(created.last().stamp)
    }

    @Test
    fun g2B07EnterConsumesComposingFlag() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.PINYIN)
        c.key('n'.code)
        c.key('i'.code)
        c.enterRaw()
        // Enter committed the raw composing text: the host editor action
        // must be suppressed.
        assertTrue(c.enterConsumedComposing)
        assertTrue(editor.operations.contains("commitText:ni"))

        // In direct mode with an empty session, Enter falls through to the
        // editor action.
        val c2 = coordinator()
        c2.onEditorStarted(sensitive = false)
        c2.enterRaw()
        assertFalse(c2.enterConsumedComposing)
    }

    @Test
    fun selectModeLandsPinyinRawBeforeSwitchingAway() {
        // Switching away from PINYIN/DOUBLE_PINYIN commits the
        // raw buffer instead of dropping it (design §5.2 - the old
        // finishComposing was a no-op: the letters vanished with the
        // session close).
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.PINYIN)
        c.key('n'.code)
        c.key('i'.code)
        c.selectMode(InputMode.DIRECT)
        assertTrue(editor.operations.contains("commitText:ni"))
    }

    @Test
    fun selectModeLandsFrenchSpanOnceWithoutDoubleWrite() {
        // Review P1-2: French composes through an editor span; the switch
        // must land it exactly ONCE (finishComposing path, no raw commit
        // appending a second copy - "bonbon" regression guard).
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.FRENCH)
        c.key('b'.code)
        c.key('o'.code)
        c.selectMode(InputMode.DIRECT)
        // No raw-buffer commit for span modes at all - the span lands via
        // the finishComposing path and a second copy never appears.
        assertFalse(editor.operations.contains("commitText:bo"))
        assertFalse(editor.operations.contains("commitText:bobo"))
    }

    @Test
    fun acceptCurrentCompositionCommitsChineseRawExactlyOnceAndResetsEngine() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.PINYIN)
        c.key('n'.code)
        c.key('i'.code)

        assertEquals(DispatchAck.Accepted, c.acceptCurrentComposition())
        assertEquals(1, editor.operations.count { it == "commitText:ni" })
        assertEquals(1, fake.requests.count { it.command == EngineCommand.Reset })

        // A lifecycle callback or repeated explicit switch must not land the
        // raw buffer a second time after the coordinator reset.
        c.acceptCurrentComposition()
        assertEquals(1, editor.operations.count { it == "commitText:ni" })
        assertEquals(1, fake.requests.count { it.command == EngineCommand.Reset })
    }

    @Test
    fun acceptCurrentCompositionFinishesFrenchSpanExactlyOnce() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.FRENCH)
        c.key('b'.code)
        c.key('o'.code)

        val before = editor.operations.count { it == "finishComposing" }
        c.acceptCurrentComposition()

        assertEquals(before + 1, editor.operations.count { it == "finishComposing" })
        assertEquals(1, fake.requests.count { it.command == EngineCommand.Reset })
        c.acceptCurrentComposition()
        assertEquals(before + 1, editor.operations.count { it == "finishComposing" })
    }

    @Test
    fun frenchWordSpaceThenBackspaceReopensTheWordInTheEngine() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.FRENCH)
        c.key('b'.code)
        c.key('o'.code)
        c.space()

        assertEquals(1, editor.operations.count { it == "commitText:bo " })
        val before = editor.operations.size
        c.backspace()

        val reopened = editor.operations.drop(before)
        assertEquals(0, reopened.count { it == "sendDeleteKey" })
        assertTrue(reopened.contains("setComposing:bo"))
        assertEquals("bo", editor.text)
        c.key('n'.code)
        assertEquals("bon", editor.text)
        assertEquals(1, editor.operations.count { it == "commitText:bo " })
        assertTrue(fake.requests.any { it.command == EngineCommand.Reset })
    }

    @Test
    fun russianWordSpaceThenBackspaceUsesTheSameUndoTransaction() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.RUSSIAN)
        c.key('я'.code)
        c.space()
        val before = editor.operations.size

        c.backspace()

        val reopened = editor.operations.drop(before)
        assertEquals(0, reopened.count { it == "sendDeleteKey" })
        assertTrue(reopened.contains("setComposing:я"))
    }

    @Test
    fun newCharacterInvalidatesWordUndoBeforeBackspace() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.FRENCH)
        c.key('b'.code)
        c.space()
        val before = editor.operations.size

        c.key('x'.code)
        c.backspace()

        val after = editor.operations.drop(before)
        assertFalse(after.contains("sendDeleteKey"))
        assertFalse(after.contains("setComposing:b"))
    }

    @Test
    fun hostSelectionChangeInvalidatesWordUndo() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.FRENCH)
        c.key('b'.code)
        c.space()
        val before = editor.operations.size

        c.onEditorSelectionChanged(0, 0, 0, 0)
        c.backspace()

        val after = editor.operations.drop(before)
        assertEquals(listOf("sendDeleteKey"), after)
    }

    @Test
    fun ownCommitSelectionCallbackDoesNotInvalidateWordUndo() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.FRENCH)
        c.key('b'.code)
        c.space()
        val before = editor.operations.size

        // Simulate InputMethodService.onUpdateSelection for the commit that
        // inserted "b ": the coordinator must distinguish it from a host
        // cursor move arriving after the commit.
        c.onEditorSelectionChanged(1, 1, 2, 2)
        c.backspace()

        val reopened = editor.operations.drop(before)
        assertEquals(0, reopened.count { it == "sendDeleteKey" })
        assertTrue(reopened.contains("setComposing:b"))
    }

    private fun assertStalePagingKeepsWordUndo(
        page: (TextInputCoordinator, Long) -> DispatchAck,
    ) {
        for (commitKind in listOf("choose", "space")) {
            val c = coordinator()
            c.onEditorStarted(sensitive = false)
            c.selectMode(InputMode.FRENCH)
            c.key('b'.code)
            c.key('o'.code)
            val oldPageRevision = fake.revision

            val commitAck = if (commitKind == "choose") {
                c.choose(oldPageRevision, "first")
            } else {
                c.space()
            }
            assertEquals(DispatchAck.Accepted, commitAck)

            // The page request was built from the pre-commit page. The engine
            // rejects it, while browsing must leave the just-committed word
            // undo transaction untouched.
            assertEquals(
                DispatchAck.Rejected(EngineCode.STALE_REVISION),
                page(c, oldPageRevision),
            )
            val before = editor.operations.size
            c.backspace()
            val reopened = editor.operations.drop(before)
            assertEquals(commitKind, 0, reopened.count { it == "sendDeleteKey" })
            assertTrue(commitKind, reopened.any { it == "setComposing:bo" })
        }
    }

    @Test
    fun staleNextPageDoesNotInvalidateChooseOrSpaceWordUndo() {
        assertStalePagingKeepsWordUndo { c, revision -> c.pageNext(revision) }
    }

    @Test
    fun stalePreviousPageDoesNotInvalidateChooseOrSpaceWordUndo() {
        assertStalePagingKeepsWordUndo { c, revision -> c.pagePrevious(revision) }
    }

    @Test
    fun pasteExternalStopsVoiceClearsEngineAndCommits() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.selectMode(InputMode.PINYIN)
        c.key('n'.code)
        // The pinyin preedit never reaches the editor.
        assertEquals(0, editor.operations.count { it.startsWith("setComposing") })
        val ack = c.pasteExternal("粘贴内容")
        assertEquals(DispatchAck.Accepted, ack)
        // onEditorStarted already guards once; the paste guards again.
        assertEquals(2, asrGuardCalls)
        // Engine buffer was reset through the normal command path…
        assertTrue(fake.requests.any { it.command == EngineCommand.Reset })
        // …the live composition LANDED before the paste. The preedit has no
        // editor span since #8, so the coordinator commits its raw buffer
        // explicitly - the old setComposing("") wipe dropped it (the
        // flick-drops-composition bug).
        assertTrue(editor.operations.contains("commitText:n"))
        assertTrue(
            editor.operations.indexOf("commitText:n") <
                editor.operations.indexOf("commitText:粘贴内容"),
        )
        // …and the pasted text committed once.
        assertEquals(1, editor.operations.count { it == "commitText:粘贴内容" })
        // The async Reset event finds no active composing span afterwards.
        c.key('a'.code)
        val last = events.last()
        assertEquals("a", last.state.composing)
    }

    @Test
    fun clearComposingResetsEngineAndRestoresDirect() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.key('n'.code)
        c.key('i'.code)
        assertTrue(editor.operations.any { it.startsWith("setComposing") })
        val ack = c.clearComposing()
        assertEquals(DispatchAck.Accepted, ack)
        // Engine buffer cleared through the Reset command path…
        assertTrue(fake.requests.any { it.command == EngineCommand.Reset })
        // …and the composing span scrubbed exactly like pasteExternal.
        assertTrue(editor.operations.count { it == "setComposing:" } >= 1)
        // Nothing is committed: the next keystroke starts fresh.
        val before = editor.operations.size
        c.key('a'.code)
        assertTrue(editor.operations.size > before)
        assertEquals("a", events.last().state.composing)
    }

    @Test
    fun clearComposingDuringVoiceKeepsEditorSpan() {
        // Review P2: while a voice session owns the editor span (ASR
        // partial), × must reset only the engine pinyin buffer - scrubbing
        // would wipe the partial text the recognizer is streaming in.
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.key('n'.code)
        c.key('i'.code)
        val before = editor.operations.count { it.startsWith("setComposing") }
        val ack = c.clearComposing(scrubEditor = false)
        assertEquals(DispatchAck.Accepted, ack)
        assertTrue(fake.requests.any { it.command == EngineCommand.Reset })
        // The direct scrub is skipped; the engine Reset still flows back as a
        // state event, whose empty-composing sync clears the dead span once
        // (by design - it is what erases a stale pinyin preedit everywhere).
        val after = editor.operations.count { it.startsWith("setComposing") }
        assertEquals(before + 1, after)
        assertEquals(0, editor.operations.count { it.startsWith("commitText") })
    }

    @Test
    fun clearComposingWithoutCompositionIsHarmless() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        val ack = c.clearComposing()
        assertEquals(DispatchAck.Accepted, ack)
        assertTrue(fake.requests.any { it.command == EngineCommand.Reset })
        assertEquals(0, editor.operations.count { it.startsWith("commitText") })
    }

    @Test
    fun pasteExternalAfterComposingDoesNotStackPreedit() {
        val c = coordinator()
        c.onEditorStarted(sensitive = false)
        c.key('n'.code)
        c.pasteExternal("已粘贴")
        // Next keystroke must open a fresh preedit over the committed text,
        // not a second composing span layered after the engine's stale buffer.
        c.key('h'.code)
        assertEquals("h", events.last().state.composing)
        val editorOps = editor.operations.filter { it.startsWith("setComposing") || it.startsWith("commitText") }
        assertEquals("setComposing:n", editorOps.first())
        assertEquals("setComposing:h", editorOps.last())
        assertTrue(editorOps.contains("commitText:已粘贴"))
        // Exactly one fresh preedit after the paste (no stacked second span).
        assertEquals("setComposing:h", editorOps.last())
    }
}
