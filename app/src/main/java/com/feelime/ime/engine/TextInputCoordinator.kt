package com.feelime.ime.engine

import java.util.concurrent.Executor

/**
 * Owns editor/session generations, validates every engine event against the
 * current EngineStamp, applies committed text to the editor and runs the
 * section 5.4 deletion cascade for unconsumed backspaces.  All public methods
 * must be called from the poster's target thread (the Android main thread in
 * production; inline in JVM tests).
 *
 * Slow engines follow the Direct-first state machine (design section 9): the
 * target engine's Start runs asynchronously against a *pending* stamp while
 * keys typed during warmup are ACCEPTED and queued (). The queue is
 * replayed into whichever engine wins — the target after READY, or the
 * no-learning Direct engine after a failure — so no keystroke is lost and no
 * word is ever split across two engines. During warmup the coordinator only
 * publishes LOADING; READY is never claimed before the target engine can
 * actually produce state.
 */
class TextInputCoordinator(
    private val editor: EditorPort,
    private val listener: (EngineEvent) -> Unit,
    private val engineFactory: (InputMode) -> TextEngine,
    private val asrGuard: () -> Unit = {},
    private val mainPoster: (() -> Unit) -> Unit = { it() },
    private val background: Executor? = null,
    private val modeStore: ModeStore? = null,
    private val delayPoster: ((Long, () -> Unit) -> Unit)? = null,
) {
    /** process-death-safe persistence of the user's selected mode. */
    interface ModeStore {
        fun save(mode: InputMode)

        fun load(): InputMode?
    }

    private var editorGeneration = 0L
    private var engineSessionGeneration = 0L
    private var mode: InputMode = InputMode.DIRECT
    private var savedUserMode: InputMode = modeStore?.load() ?: InputMode.DIRECT
    /** True once the LIVE engine is the real target (not interim/fallback). */
    private var engineMatchesMode = true
    private var inPasswordField = false

    private var engine: TextEngine = DirectTextEngine()
    private var stamp: EngineStamp = EngineStamp(editorGeneration, engineSessionGeneration, mode)
    private var lastAppliedRevision = 0L
    private var closed = false

    private var pendingEngine: TextEngine? = null
    private var pendingStamp: EngineStamp? = null
    private var pendingLastRevision = 0L

    private sealed interface QueuedInput {
        data class Command(val value: EngineCommand) : QueuedInput
        data class Mode(val value: InputMode) : QueuedInput
        data class Accept(val after: () -> Unit) : QueuedInput
        data class Literal(val text: String) : QueuedInput
    }
    private val warmupQueue = ArrayDeque<QueuedInput>()
    private var warmupReplayGeneration = 0L
    private var replaying = false
    private var composingActive = false
    /** The raw pinyin no longer enters the editor, so the
     * coordinator keeps the buffer here - it must be landable when a literal
     * (flick/symbol/popup pick) arrives mid-composition. */
    private var composingRaw: String = ""
    private var lastEnterConsumedComposing = false

    /**
     * A Hunspell word commit is editor text plus one trailing space. Keep a
     * small local undo transaction so the first Backspace can remove that
     * space and reopen the word in the same engine. The transaction is tied to
     * both editor/session generations and a local mutation generation; it is
     * never reconstructed by synchronously reading host text.
     */
    private data class LastWordCommit(
        val word: String,
        val editorGeneration: Long,
        val engineSessionGeneration: Long,
        val mode: InputMode,
        val mutationGeneration: Long,
        val start: Int,
        val end: Int,
        val automaticSpace: Boolean,
    )

    private var editorMutationGeneration = 0L
    private var lastWordCommit: LastWordCommit? = null
    private var predictedSelectionStart = -1
    private var predictedSelectionEnd = -1
    private val expectedSelections = ArrayDeque<Pair<Int, Int>>()

    val currentMode: InputMode get() = mode
    val currentStamp: EngineStamp get() = stamp
    val engineWarming: Boolean get() = pendingEngine != null
    val enterConsumedComposing: Boolean get() = lastEnterConsumedComposing

    /** Host selection/caret changed outside a coordinator operation. */
    fun onEditorSelectionChanged(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
    ) {
        val actual = newSelStart to newSelEnd
        val own = expectedSelections.lastIndexOf(actual)
        if (own >= 0) {
            repeat(own + 1) { expectedSelections.removeFirst() }
            return
        }
        predictedSelectionStart = newSelStart
        predictedSelectionEnd = newSelEnd
        onExternalEditorMutation()
    }

    /** A host mutation invalidates both word ownership and pending predictions. */
    fun onExternalEditorMutation() {
        expectedSelections.clear()
        invalidateWordUndo()
    }

    private fun invalidateWordUndo() {
        editorMutationGeneration += 1
        lastWordCommit = null
    }

    /** Switch between the host and keyboard-owned input, or receive an
     * explicit DOM caret edit. Coordinates never carry across input targets. */
    fun onInputTargetSelection(start: Int, end: Int) {
        onExternalEditorMutation()
        predictedSelectionStart = start
        predictedSelectionEnd = end
        // The DOM accepted any old span before reporting an explicit caret
        // change. Reset only the engine so the next key starts a new word.
        if (composingActive) {
            composingActive = false
            composingRaw = ""
            dispatch(EngineCommand.Reset)
        }
    }

    private fun expectReplacement(length: Int) {
        if (predictedSelectionStart < 0 || predictedSelectionEnd < 0) return
        val start = if (composingActive && mode != InputMode.PINYIN && mode != InputMode.DOUBLE_PINYIN) predictedSelectionEnd - composingRaw.length
            else minOf(predictedSelectionStart, predictedSelectionEnd)
        val target = (start + length).coerceAtLeast(0)
        predictedSelectionStart = target
        predictedSelectionEnd = target
        expectedSelections.addLast(target to target)
    }

    fun onEditorStarted(sensitive: Boolean, terminalLike: Boolean = false, initialSelectionStart: Int = 0, initialSelectionEnd: Int = initialSelectionStart) {
        asrGuard()
        invalidateWordUndo()
        expectedSelections.clear()
        predictedSelectionStart = initialSelectionStart
        predictedSelectionEnd = initialSelectionEnd
        warmupQueue.clear()
        abandonPending()
        if (sensitive) {
            // The production password-field entry must perform the same
            // synchronous scrub as enterPasswordField(): clear any composing
            // span before an old callback can be rendered into the new field.
            editor.setComposing("")
            editor.finishComposing()
            composingActive = false
        }
        // close the live engine before opening the new session so
        // native sessions cannot leak across editor transitions.
        closeEngineSession()
        editorGeneration += 1
        newSession()
        inPasswordField = sensitive
        if (sensitive) {
            // Password fields must stay on Direct: composition spans must
            // never render into them (see enterPasswordField).
            startEngine(InputMode.DIRECT)
        } else {
            // Terminal (TYPE_NULL) editors no longer force Direct -
            // the user picks the mode. Deletion already rides real Backspace
            // key events and cursor scrub rides arrow key events on such
            // editors, so committed text (candidates/Direct keys) reaches the
            // shell regardless of the engine. Composing-span alphabetic
            // modes (French/Russian) still do not echo on a dummy
            // InputConnection - a declared limitation, not a restriction.
            startEngine(savedUserMode)
        }
    }

    enum class ModeSelectionResult {
        ACCEPTED,
        ALREADY_ACTIVE,
        BLOCKED_SENSITIVE_EDITOR,
    }

    fun selectMode(next: InputMode): ModeSelectionResult {
        // A same-mode request is harmless even when the current editor is a
        // restricted one.  The keyboard can use this result to avoid showing
        // a warning for a tap on the already-selected Direct row.
        if (next == mode && engineIsActive() && engineMatchesMode) {
            return ModeSelectionResult.ALREADY_ACTIVE
        }
        // Only password fields restrict the mode; terminal editors accept
        // every mode (user request: terminals are not forced to
        // English Direct any more).
        if (inPasswordField) return ModeSelectionResult.BLOCKED_SENSITIVE_EDITOR
        invalidateWordUndo()

        if (pendingEngine != null || replaying) {
            warmupQueue.addLast(QueuedInput.Mode(next))
            savedUserMode = next
            modeStore?.save(next)
            return ModeSelectionResult.ACCEPTED
        }

        // A same-mode switch must still re-attempt the real engine when the
        // interim/fallback Direct engine is serving (pending never landed).
        acceptCurrentComposition()
        savedUserMode = next
        modeStore?.save(next)
        closeEngineSession()
        abandonPending()
        newSession()
        startEngine(next)
        return ModeSelectionResult.ACCEPTED
    }

    /**
     * Accept and clear the composition owned by this coordinator.  Chinese
     * engines expose raw pinyin only to the keyboard UI, so it is committed
     * explicitly.  Alphabetic modes expose an editor composing span, so
     * finishing that span performs the single landing operation.
     *
     * Local state is cleared before Reset because its empty event may arrive
     * synchronously or later on the main thread; either way it must not land
     * the same text twice.  This operation is explicit so an ordinary input
     * view teardown does not turn into a global commit.
     */
    fun acceptCurrentComposition(): DispatchAck = acceptCurrentComposition { }

    /**
     * Variant used by system-IME switching.  If the target engine is still
     * warming, wait for its queued commands to replay before invoking
     * [after]; switching the system IME immediately would strand that queue.
     */
    fun acceptCurrentComposition(after: () -> Unit): DispatchAck {
        invalidateWordUndo()
        if (pendingEngine != null || replaying) {
            warmupQueue.addLast(QueuedInput.Accept(after))
            return DispatchAck.Accepted
        }
        val ack = acceptCurrentCompositionNow()
        after()
        return ack
    }

    private fun acceptCurrentCompositionNow(): DispatchAck {
        if (!composingActive) return DispatchAck.Accepted

        val raw = composingRaw.replace(" ", "")
        val rawMode = mode == InputMode.PINYIN || mode == InputMode.DOUBLE_PINYIN
        composingActive = false
        composingRaw = ""
        val ack = dispatchLive(EngineCommand.Reset)
        if (rawMode) {
            if (raw.isNotEmpty()) editor.commitText(raw)
        } else {
            editor.finishComposing()
        }
        return ack
    }

    fun enterPasswordField() {
        if (inPasswordField) return
        asrGuard()
        invalidateWordUndo()
        inPasswordField = true
        warmupQueue.clear()
        abandonPending()
        editor.setComposing("")
        editor.finishComposing()
        composingActive = false
        closeEngineSession()
        editorGeneration += 1
        newSession()
        startEngine(InputMode.DIRECT)
    }

    fun leavePasswordField() {
        if (!inPasswordField) return
        invalidateWordUndo()
        inPasswordField = false
        warmupQueue.clear()
        closeEngineSession()
        abandonPending()
        editorGeneration += 1
        newSession()
        startEngine(savedUserMode)
    }

    /**
     * Begin voice mode after the current composition has landed. The
     * continuation may sit in warmupQueue while a target engine is loading;
     * callers that own a wider editor lifecycle should provide [isCurrent] so
     * a late continuation cannot close or reset a replacement editor's
     * engine.
     */
    fun beginVoiceSession(after: () -> Unit = {}) {
        beginVoiceSession(isCurrent = { true }, after = after)
    }

    fun beginVoiceSession(isCurrent: () -> Boolean, after: () -> Unit) {
        acceptCurrentComposition {
            if (!isCurrent()) return@acceptCurrentComposition
            closeEngineSession()
            abandonPending()
            newSession()
            after()
        }
    }

    fun endVoiceSession() {
        invalidateWordUndo()
        newSession()
        startEngine(if (inPasswordField) InputMode.DIRECT else mode)
    }

    fun key(unicodeScalar: Int): DispatchAck {
        if (!isWordPunctuation(unicodeScalar)) invalidateWordUndo()
        return dispatch(EngineCommand.Key(unicodeScalar, 0))
    }

    /**
     * Parse-variant switch: rewind the composition and retype [keys]
     * inside ONE bridge call. Every command still emits its own engine event,
     * but they are all enqueued before the WebView renders again - the JS
     * side freezes on its chosen parse until the final echo lands, so the
     * user never sees the delete-and-retype.
     */
    fun setComposition(keys: String): DispatchAck {
        asrGuard()
        invalidateWordUndo()
        dispatch(EngineCommand.Reset)
        editor.setComposing("")
        editor.finishComposing()
        composingActive = false
        var ack: DispatchAck = DispatchAck.Accepted
        for (ch in keys) {
            ack = dispatch(EngineCommand.Key(ch.code, 0))
            if (ack != DispatchAck.Accepted) break
        }
        return ack
    }

    fun space(): DispatchAck {
        invalidateWordUndo()
        return dispatch(EngineCommand.Space)
    }

    fun backspace(): DispatchAck {
        val undo = lastWordCommit
        if (!replaying && undo != null && canReopen(undo)) return reopenLastWord(undo)
        invalidateWordUndo()
        return dispatch(EngineCommand.Backspace)
    }

    fun enterRaw(): DispatchAck {
        invalidateWordUndo()
        lastEnterConsumedComposing = false
        return dispatch(EngineCommand.EnterRaw)
    }

    fun choose(expectedRevision: Long, candidateId: String): DispatchAck {
        invalidateWordUndo()
        return dispatch(EngineCommand.Choose(expectedRevision, candidateId))
    }

    /** 删除当前高亮候选（librime Shift+Delete 通道）。 */
    fun deleteHighlighted(): DispatchAck {
        invalidateWordUndo()
        return dispatch(EngineCommand.DeleteHighlighted)
    }

    /** 删除任意候选（seek 到所在页 + Down 移高亮 + Shift+Delete）。 */
    fun deleteCandidate(candidateId: String): DispatchAck {
        invalidateWordUndo()
        return dispatch(EngineCommand.DeleteCandidate(candidateId))
    }

    fun pageNext(expectedRevision: Long): DispatchAck {
        return dispatch(EngineCommand.PageNext(expectedRevision))
    }

    fun pagePrevious(expectedRevision: Long): DispatchAck {
        return dispatch(EngineCommand.PagePrevious(expectedRevision))
    }

    fun reset(): DispatchAck {
        invalidateWordUndo()
        return dispatch(EngineCommand.Reset)
    }

    /**
     * External paste (clipboard/favorites panel, design §3.7): stop any ASR
     * session, clear the engine buffer through the normal command path, scrub
     * the editor's composing span synchronously, then commit. Going through
     * Reset keeps composingActive/rawInput consistent — a bare
     * finishComposing+commit would leave the engine's old buffer alive and
     * the next keystroke would open a second preedit over the pasted text.
     */
    fun pasteExternal(text: String): DispatchAck {
        asrGuard()
        if (pendingEngine != null || replaying) {
            warmupQueue.addLast(QueuedInput.Literal(text))
            return DispatchAck.Accepted
        }
        return pasteExternalNow(text)
    }

    private fun pasteExternalNow(text: String): DispatchAck {
        removeAutomaticSpaceBefore(text)
        invalidateWordUndo()
        // A live composition must LAND before the literal -
        // flicks/long-press popups/symbols all arrive here, and the old
        // setComposing("") wiped the preedit (x + flick-up e produced "3",
        // dropping the x). Stopped mirroring the pinyin into
        // the editor, so there is no span to finishComposing any more: the
        // raw buffer is committed EXPLICITLY instead, then the engine reset
        // cannot touch the editor (composingActive is already false when its
        // echo arrives).
        if (composingActive) {
            // librime's preedit interleaves display-only spaces at syllable
            // boundaries ("xi an") - they are not user input and must not
            // land (the Enter path has the same quirk by design).
            if (composingRaw.isNotEmpty()) {
                val raw = composingRaw.replace(" ", "")
                expectReplacement(raw.length)
                editor.commitText(raw)
            }
            composingRaw = ""
            composingActive = false
        }
        val ack = dispatchLive(EngineCommand.Reset)
        expectReplacement(text.length)
        editor.commitText(text)
        return ack
    }

    /**
     * Candidate-bar × (design: candidate compose controls): abort the current
     * composition and restore Direct/tools. Same scrub ordering as
     * [pasteExternal] — the engine buffer must go through the Reset command
     * path or the next keystroke would resurrect the old preedit.
     */
    fun clearComposing(scrubEditor: Boolean = true): DispatchAck {
        invalidateWordUndo()
        val ack = dispatch(EngineCommand.Reset)
        if (scrubEditor) {
            editor.setComposing("")
            editor.finishComposing()
        }
        composingActive = false
        return ack
    }

    fun close() {
        warmupQueue.clear()
        invalidateWordUndo()
        closeEngineSession()
        abandonPending()
        newSession()
    }

    private fun engineIsActive(): Boolean = !closed

    private fun dispatch(command: EngineCommand): DispatchAck {
        if (closed && command != EngineCommand.Start) {
            return DispatchAck.Rejected(EngineCode.STALE_STAMP)
        }
        if (pendingEngine != null || replaying) {
            warmupQueue.addLast(QueuedInput.Command(command))
            if (pendingEngine != null && warmupQueue.size > MAX_WARMUP_QUEUE) fallbackFromWarmup()
            return DispatchAck.Accepted
        }
        // Live events are posted too. Keep subsequent keys and mode/session
        // changes behind the applied event, just like warmup replay.
        replaying = true
        return if (command == EngineCommand.EnterRaw) dispatchLiveEnter { replayWarmupQueue() }
        else dispatchLive(command) { replayWarmupQueue() }
    }

    private fun dispatchLive(
        command: EngineCommand,
        onComplete: (() -> Unit)? = null,
    ): DispatchAck {
        if (command == EngineCommand.Backspace) {
            val undo = lastWordCommit
            if (undo != null && canReopen(undo)) return reopenLastWord(undo, onComplete)
        }
        if (command is EngineCommand.Key &&
            (mode == InputMode.FRENCH || mode == InputMode.RUSSIAN) &&
            isWordPunctuation(command.unicodeScalar) &&
            !(composingActive && (command.unicodeScalar == '\''.code || command.unicodeScalar == '’'.code))) {
            val ack = pasteExternalNow(String(Character.toChars(command.unicodeScalar)))
            onComplete?.invoke()
            return ack
        }
        val requestStamp = stamp
        var completed = false
        fun complete() {
            if (completed || requestStamp != stamp) return
            completed = true
            onComplete?.invoke()
        }
        val ack = engine.dispatch(EngineRequest(stamp, command)) { event ->
            mainPoster {
                onEngineEvent(event, command)
                complete()
            }
        }
        if (ack != DispatchAck.Accepted) complete()
        return ack
    }

    /**
     * Enter's host action must be decided after preceding queued commands have
     * updated composingActive.  The callback therefore performs the decision
     * after the engine event, which also works when mainPoster is asynchronous
     * in production.
     */
    private fun dispatchLiveEnter(onComplete: (() -> Unit)? = null): DispatchAck {
        val hadComposing = composingActive
        lastEnterConsumedComposing = false
        val requestStamp = stamp
        var completed = false
        fun complete() {
            if (completed || requestStamp != stamp) return
            completed = true
            if (hadComposing) {
                lastEnterConsumedComposing = true
            } else {
                editor.performEditorAction()
            }
            onComplete?.invoke()
        }
        val ack = engine.dispatch(EngineRequest(stamp, EngineCommand.EnterRaw)) { event ->
            mainPoster {
                onEngineEvent(event, EngineCommand.EnterRaw)
                complete()
            }
        }
        if (ack != DispatchAck.Accepted) {
            // A rejected Enter never reaches the editor.  Still release a
            // warmup replay continuation, but do not synthesize an action.
            if (!completed) {
                completed = true
                onComplete?.invoke()
            }
        }
        return ack
    }

    /**
     * Replay warmup commands one at a time.  Posting the next dispatch only
     * after the previous event has been applied preserves command semantics
     * (especially Backspace/Space/Enter) even when mainPoster posts to the
     * Android main looper instead of running inline.
     */
    private fun replayWarmupQueue() {
        replaying = true
        val generation = ++warmupReplayGeneration
        fun next() {
            if (generation != warmupReplayGeneration) return
            val item = warmupQueue.removeFirstOrNull()
            if (item == null) {
                replaying = false
                return
            }
            when (item) {
                is QueuedInput.Command -> {
                    if (item.value == EngineCommand.EnterRaw) dispatchLiveEnter(::next)
                    else dispatchLive(item.value, ::next)
                }
                is QueuedInput.Literal -> {
                    pasteExternalNow(item.text)
                    if (generation == warmupReplayGeneration) next()
                }
                is QueuedInput.Accept -> {
                    acceptCurrentCompositionNow()
                    item.after()
                    if (generation == warmupReplayGeneration) next()
                }
                is QueuedInput.Mode -> {
                    replaying = false
                    selectMode(item.value)
                    if (pendingEngine == null) replayWarmupQueue()
                }
            }
        }
        next()
    }

    /** Warmup failed or overflowed: settle on the Direct engine and replay. */
    private fun fallbackFromWarmup() {
        val stalled = pendingEngine
        val pending = pendingStamp
        pendingEngine = null
        pendingStamp = null
        pendingLastRevision = 0
        if (stalled != null && pending != null && background != null) {
            background.execute {
                stalled.dispatch(EngineRequest(pending, EngineCommand.Close)) { }
            }
        }
        fallbackToDirect()
        replayWarmupQueue()
    }

    private fun newSession() {
        warmupReplayGeneration += 1
        replaying = false
        engineSessionGeneration += 1
        stamp = EngineStamp(editorGeneration, engineSessionGeneration, mode)
        lastAppliedRevision = 0
        closed = false
    }

    private fun startEngine(next: InputMode) {
        mode = next
        stamp = EngineStamp(editorGeneration, engineSessionGeneration, mode)
        lastAppliedRevision = 0
        closed = false
        val target = runCatching { engineFactory(next) }.getOrElse {
            engine = DirectTextEngine()
            startDirect()
            return
        }
        engineMatchesMode = next == InputMode.DIRECT || background == null
        if (next == InputMode.DIRECT || background == null) {
            engine = target
            val ack = target.dispatch(EngineRequest(stamp, EngineCommand.Start)) { event ->
                mainPoster { onEngineEvent(event) }
            }
            if (ack != DispatchAck.Accepted) {
                fallbackToDirect()
            }
            return
        }
        // Direct-first (): an interim Direct engine stands by but is
        // NOT started and publishes nothing — READY would be a lie. Keys
        // typed during warmup are queued and replayed into the winner.
        engine = DirectTextEngine()
        val targetStamp = EngineStamp(editorGeneration, engineSessionGeneration + 1, mode)
        pendingStamp = targetStamp
        pendingLastRevision = 0
        pendingEngine = target
        delayPoster?.invoke(5_000L) {
            if (pendingEngine === target && pendingStamp == targetStamp) fallbackFromWarmup()
        }
        mainPoster {
            listener(
                EngineEvent(
                    stamp,
                    1L,
                    Phase.LOADING,
                    EngineState("", "", emptyList(), false, false, null),
                    false,
                    EngineCode.OK,
                ),
            )
        }
        background.execute {
            // Capture immutable identity at task creation. Never read the
            // mutable current pendingStamp from a delayed background task.
            val ack = target.dispatch(EngineRequest(targetStamp, EngineCommand.Start)) { event ->
                mainPoster {
                    if (pendingEngine === target && pendingStamp == targetStamp) onEngineEvent(event)
                }
            }
            if (ack != DispatchAck.Accepted) {
                mainPoster {
                    if (pendingEngine === target && pendingStamp == targetStamp) {
                        onEngineEvent(pendingAbandoned(targetStamp))
                    }
                }
            }
        }
    }

    private fun pendingAbandoned(targetStamp: EngineStamp): EngineEvent =
        EngineEvent(
            targetStamp,
            1L,
            Phase.ERROR,
            EngineState("", "", emptyList(), false, false, null),
            false,
            EngineCode.ENGINE_INIT_FAILED,
        )

    private fun startDirect() {
        engine.dispatch(EngineRequest(stamp, EngineCommand.Start)) { event ->
            mainPoster { onEngineEvent(event) }
        }
    }

    private fun fallbackToDirect() {
        engineMatchesMode = mode == InputMode.DIRECT
        engineSessionGeneration += 1
        mode = InputMode.DIRECT
        stamp = EngineStamp(editorGeneration, engineSessionGeneration, mode)
        lastAppliedRevision = 0
        engine = DirectTextEngine()
        startDirect()
    }

    private fun closeEngineSession() {
        engine.dispatch(EngineRequest(stamp, EngineCommand.Close)) { event ->
            mainPoster { onEngineEvent(event) }
        }
        closed = true
    }

    private fun abandonPending() {
        val engine = pendingEngine
        val pending = pendingStamp
        pendingEngine = null
        pendingStamp = null
        pendingLastRevision = 0
        if (engine == null || pending == null) return
        background?.execute {
            engine.dispatch(EngineRequest(pending, EngineCommand.Close)) { }
        }
    }

    fun onEngineEvent(event: EngineEvent, command: EngineCommand? = null) {
        // The stamp check is what makes late async callbacks harmless: after
        // any generation or mode change their stamp no longer matches.
        if (pendingStamp != null && event.stamp == pendingStamp) {
            onPendingEvent(event)
            return
        }
        if (event.stamp != stamp) return
        if (event.revision <= lastAppliedRevision) return
        lastAppliedRevision = event.revision
        when (event.phase) {
            Phase.CLOSED -> {
                closed = true
                lastWordCommit = null
                listener(event)
                engineSessionGeneration += 1
                stamp = EngineStamp(editorGeneration, engineSessionGeneration, mode)
                lastAppliedRevision = 0
            }
            Phase.ERROR -> {
                lastWordCommit = null
                listener(event)
                if (event.code == EngineCode.ENGINE_INIT_FAILED ||
                    event.code == EngineCode.ENGINE_DATA_MISMATCH ||
                    event.code == EngineCode.ENGINE_RUNTIME_FAILED
                ) {
                    if (composingActive) editor.finishComposing()
                    if (pendingEngine != null) {
                        fallbackFromWarmup()
                    } else {
                        val resumeQueue = replaying
                        fallbackToDirect()
                        if (resumeQueue) replayWarmupQueue()
                    }
                }
            }
            else -> {
                event.state.commit?.let { committed ->
                    // A commit changes the host editor even when the engine
                    // event itself is asynchronous. Start a new local
                    // mutation generation before arming a possible word undo.
                    editorMutationGeneration += 1
                    lastWordCommit = null
                    expectReplacement(committed.length)
                    editor.commitText(committed)
                    editor.finishComposing()
                    armWordUndo(committed, automaticSpace = command is EngineCommand.Choose)
                }
                if (event.state.commit == null && event.state.composing.isNotEmpty()) {
                    lastWordCommit = null
                    // Pinyin preedit lives on the keyboard UI
                    // (candidate bar / preedit line), NOT in the editor - the
                    // raw letters must not land before a word is chosen.
                    // Alphabetical spellcheck modes (French/Russian) and the
                    // editors' own composing spans keep the classic span.
                    if (mode != InputMode.PINYIN && mode != InputMode.DOUBLE_PINYIN) {
                        expectReplacement(event.state.composing.length)
                        editor.setComposing(event.state.composing)
                    }
                }
                if (event.state.commit == null && event.state.composing.isEmpty()) {
                    // finishComposingText() accepts the old span as committed
                    // text. When an engine consumed the last composing
                    // character (for example Backspace: "n" -> ""), clear
                    // that span first so the final character is not orphaned
                    // into the editor as a literal prefix.
                    if (composingActive) {
                        if (mode != InputMode.PINYIN && mode != InputMode.DOUBLE_PINYIN) expectReplacement(0)
                        editor.setComposing("")
                        editor.finishComposing()
                    }
                }
                if (!event.consumed && event.code == EngineCode.EMPTY_COMPOSING) {
                    deleteOneEditorUnit()
                }
                composingActive = event.state.commit == null && event.state.composing.isNotEmpty()
                composingRaw = if (composingActive) event.state.composing else ""
                listener(event)
            }
        }
    }

    private fun onPendingEvent(event: EngineEvent) {
        if (event.revision <= pendingLastRevision) return
        when (event.phase) {
            Phase.LOADING -> {
                pendingLastRevision = event.revision
                listener(event)
            }
            Phase.READY -> {
                pendingLastRevision = event.revision
                // The target engine can now serve keys: swap over, then
                // replay whatever was typed during warmup into it ().
                engineMatchesMode = true
                engine = pendingEngine!!
                stamp = pendingStamp!!
                pendingEngine = null
                pendingStamp = null
                lastAppliedRevision = event.revision
                composingActive = false
                replaying = true
                listener(event)
                replayWarmupQueue()
            }
            Phase.ERROR -> {
                pendingLastRevision = event.revision
                listener(event)
                // the live engine is an unstarted interim Direct —
                // settle on a real Direct engine and replay the queue.
                fallbackFromWarmup()
            }
            Phase.CLOSED -> Unit
        }
    }

    /** Committed text is deleted with a REAL Backspace key
     * event. deleteSurroundingText rewrote the editor's text buffer without
     * the host ever noticing (xterm.js style terminals only listen for
     * keydown/input deltas), so the terminal never deleted - and no
     * IME-side probe can detect that. KEYCODE_DEL is honoured by both
     * worlds, so the cascade no longer calls the surrounding-text path at
     * all; [EditorPort.deleteSurroundingCodePoints] remains only as a
     * primitive for future explicit needs.
     * The selectedText pre-check is GONE - it is a
     * synchronous InputConnection call, and against the in-process settings
     * WebView (design §6.2) Chromium never answers, so every backspace
     * burned the framework's 2000ms watchdog before the key went out.
     * KEYCODE_DEL deletes a selection natively (device-verified: 5 chars
     * removed, caret converged), so the branch was pure cost. */
    private fun deleteOneEditorUnit() {
        editor.sendDeleteKey()
    }

    private fun canReopen(record: LastWordCommit): Boolean =
        pendingEngine == null &&
            record.editorGeneration == editorGeneration &&
            record.engineSessionGeneration == engineSessionGeneration &&
            record.mode == mode &&
            record.mutationGeneration == editorMutationGeneration &&
            (mode == InputMode.FRENCH || mode == InputMode.RUSSIAN)

    /** Remove the known trailing space, then replay the word into Hunspell. */
    private fun reopenLastWord(record: LastWordCommit, onFailed: (() -> Unit)? = null): DispatchAck {
        invalidateWordUndo()
        if (!editor.reopenComposing(record.start, record.end, record.word)) {
            editor.sendDeleteKey()
            onFailed?.invoke()
            return DispatchAck.Accepted
        }
        predictedSelectionStart = record.start + record.word.length
        predictedSelectionEnd = predictedSelectionStart
        expectedSelections.addLast(predictedSelectionStart to predictedSelectionEnd)
        // Reset the engine while keeping the editor-owned span intact. Only
        // after its callback may replay replace that span, one key at a time.
        composingActive = false
        composingRaw = ""
        replaying = true
        return dispatchLive(EngineCommand.Reset) {
            composingActive = true
            composingRaw = record.word
            val commands = mutableListOf<QueuedInput>()
            var offset = 0
            while (offset < record.word.length) {
                val codePoint = record.word.codePointAt(offset)
                commands.add(QueuedInput.Command(EngineCommand.Key(codePoint, 0)))
                offset += Character.charCount(codePoint)
            }
            commands.asReversed().forEach { warmupQueue.addFirst(it) }
            replayWarmupQueue()
        }
    }

    private fun isWordPunctuation(scalar: Int): Boolean =
        scalar <= Char.MAX_VALUE.code && scalar.toChar() in ",.;:!?…)]}’'"

    /** Only a candidate pick owns an automatic space. Never infer ownership
     * from host text, and retain French spacing before semicolon, colon, ! and ?. */
    private fun removeAutomaticSpaceBefore(text: String) {
        val record = lastWordCommit ?: return
        if (!record.automaticSpace || !canReopen(record) || composingActive ||
            predictedSelectionStart != record.end || predictedSelectionEnd != record.end) return
        val joinedPunctuation = if (mode == InputMode.FRENCH) ",.…)]}’'"
            else ",.;:!?…)]}’'"
        if (text != "..." && (text.length != 1 || text[0] !in joinedPunctuation)) return
        if (!editor.reopenComposing(record.start, record.end, record.word)) return
        editor.finishComposing()
        predictedSelectionStart = record.end - 1
        predictedSelectionEnd = predictedSelectionStart
        expectedSelections.addLast(predictedSelectionStart to predictedSelectionEnd)
    }

    private fun armWordUndo(commit: String, automaticSpace: Boolean) {
        if (mode != InputMode.FRENCH && mode != InputMode.RUSSIAN) {
            lastWordCommit = null
            return
        }
        if (!commit.endsWith(" ") || commit.length == 1 || predictedSelectionEnd < commit.length) {
            lastWordCommit = null
            return
        }
        lastWordCommit = LastWordCommit(
            word = commit.dropLast(1),
            editorGeneration = editorGeneration,
            engineSessionGeneration = engineSessionGeneration,
            mode = mode,
            mutationGeneration = editorMutationGeneration,
            start = predictedSelectionEnd - commit.length,
            end = predictedSelectionEnd,
            automaticSpace = automaticSpace,
        )
    }

    private companion object {
        const val MAX_WARMUP_QUEUE = 128
    }
}
