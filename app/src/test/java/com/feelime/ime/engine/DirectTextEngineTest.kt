package com.feelime.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectTextEngineTest {
    private fun started(): Pair<DirectTextEngine, EngineStamp> {
        val engine = DirectTextEngine()
        val stamp = EngineStamp(1, 1, InputMode.DIRECT)
        val ack = engine.dispatch(EngineRequest(stamp, EngineCommand.Start)) { }
        assertEquals(DispatchAck.Accepted, ack)
        return engine to stamp
    }

    private fun events(engine: DirectTextEngine, stamp: EngineStamp, command: EngineCommand): List<EngineEvent> {
        val collected = mutableListOf<EngineEvent>()
        val ack = engine.dispatch(EngineRequest(stamp, command)) { collected.add(it) }
        assertEquals(DispatchAck.Accepted, ack)
        return collected
    }

    @Test
    fun typingCommitsEachKeyImmediatelyWithoutPreedit() {
        val (engine, stamp) = started()
        val e1 = events(engine, stamp, EngineCommand.Key('h'.code, 0)).single()
        assertEquals("h", e1.state.commit)
        assertEquals("", e1.state.composing)
        val e2 = events(engine, stamp, EngineCommand.Key('i'.code, 0)).single()
        assertEquals("i", e2.state.commit)
        assertEquals("", e2.state.composing)
        assertTrue(e2.revision > e1.revision)
    }

    @Test
    fun spaceCommitsASingleSpace() {
        val (engine, stamp) = started()
        events(engine, stamp, EngineCommand.Key('o'.code, 0))
        val e = events(engine, stamp, EngineCommand.Space).single()
        assertEquals(" ", e.state.commit)
        assertEquals("", e.state.composing)
    }

    @Test
    fun enterRawHasNothingToCommitInDirectMode() {
        val (engine, stamp) = started()
        events(engine, stamp, EngineCommand.Key('x'.code, 0))
        val e = events(engine, stamp, EngineCommand.EnterRaw).single()
        assertEquals(null, e.state.commit)
    }

    @Test
    fun backspaceAlwaysFallsThroughToEditorDeletion() {
        val (engine, stamp) = started()
        val rocket = 0x1F680
        events(engine, stamp, EngineCommand.Key(rocket, 0))
        val first = events(engine, stamp, EngineCommand.Backspace).single()
        assertEquals(EngineCode.EMPTY_COMPOSING, first.code)
        assertTrue(!first.consumed)
        val second = events(engine, stamp, EngineCommand.Backspace).single()
        assertEquals(EngineCode.EMPTY_COMPOSING, second.code)
        assertTrue(!second.consumed)
    }

    @Test
    fun g2B08SupplementaryCodePointCommitsIntact() {
        val (engine, stamp) = started()
        val e = events(engine, stamp, EngineCommand.Key(0x1F680, 0)).single()
        assertEquals("🚀", e.state.commit)
        assertEquals(2, e.state.commit?.length)
        assertEquals(1, e.state.commit?.codePointCount(0, e.state.commit!!.length))
    }

    @Test
    fun g2B08UnpairedSurrogateHalvesAreRejected() {
        val (engine, stamp) = started()
        for (bogus in intArrayOf(0xD800, 0xDBFF, 0xDC00, 0xDFFF)) {
            val ack = engine.dispatch(EngineRequest(stamp, EngineCommand.Key(bogus, 0))) { }
            assertEquals(DispatchAck.Rejected(EngineCode.INVALID_COMMAND), ack)
        }
    }

    @Test
    fun staleStampIsRejectedWithoutEvents() {
        val (engine, stamp) = started()
        val stale = stamp.copy(engineSessionGeneration = stamp.engineSessionGeneration + 1)
        var emitted = 0
        val ack = engine.dispatch(EngineRequest(stale, EngineCommand.Key('a'.code, 0))) { emitted += 1 }
        assertEquals(DispatchAck.Rejected(EngineCode.STALE_STAMP), ack)
        assertEquals(0, emitted)
    }

    @Test
    fun staleRevisionChooseIsRejected() {
        val (engine, stamp) = started()
        val ack = engine.dispatch(EngineRequest(stamp, EngineCommand.Choose(99, "c:0"))) { }
        assertEquals(DispatchAck.Rejected(EngineCode.STALE_REVISION), ack)
    }

    @Test
    fun pageCommandsAtBoundaryEmitUnconsumedBoundaryEvent() {
        val (engine, stamp) = started()
        val typed = events(engine, stamp, EngineCommand.Key('a'.code, 0)).single()
        val pages = events(engine, stamp, EngineCommand.PageNext(typed.revision))
        val e = pages.single()
        assertEquals(EngineCode.PAGE_BOUNDARY, e.code)
        assertTrue(!e.consumed)
    }

    @Test
    fun closeEmitsSingleClosedThenRejects() {
        val (engine, stamp) = started()
        val closed = events(engine, stamp, EngineCommand.Close).single()
        assertEquals(EngineCode.ENGINE_CLOSED, closed.code)
        assertEquals(Phase.CLOSED, closed.phase)
        val ack = engine.dispatch(EngineRequest(stamp, EngineCommand.Key('a'.code, 0))) { }
        assertEquals(DispatchAck.Rejected(EngineCode.STALE_STAMP), ack)
    }
}
