package com.feelime.ime

import android.content.Context
import com.feelime.ime.engine.DirectTextEngine
import com.feelime.ime.engine.HunspellTextEngine
import com.feelime.ime.engine.InputMode
import com.feelime.ime.engine.MozcTextEngine
import com.feelime.ime.engine.RimeTextEngine
import com.feelime.ime.engine.TextEngine

object InputModeBridge {
    private val byWire = com.feelime.ime.engine.InputMode.entries.associateBy { it.wireName }
    fun fromWire(wire: String): InputMode? = byWire[wire]
}

object EngineFactory {
    fun create(context: Context, mode: InputMode): TextEngine = when (mode) {
        InputMode.DIRECT -> DirectTextEngine()
        InputMode.PINYIN -> RimeTextEngine(context, "luna_pinyin")
        InputMode.DOUBLE_PINYIN -> RimeTextEngine(context, "ziranma_double_pinyin")
        InputMode.FRENCH -> HunspellTextEngine(context, "fr", "bonjour")
        InputMode.RUSSIAN -> HunspellTextEngine(context, "ru_RU", "ёлка")
        InputMode.JAPANESE -> MozcTextEngine(context)
    }
}
