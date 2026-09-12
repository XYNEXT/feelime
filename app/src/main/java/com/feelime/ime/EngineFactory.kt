package com.feelime.ime

import android.content.Context
import com.feelime.ime.engine.DirectTextEngine
import com.feelime.ime.engine.DoublePinyinScheme
import com.feelime.ime.engine.FuzzyPinyin
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
        // 模糊音开启时用预编译的 fuzzy 变体 schema；其资产缺失（部署不完整）
        // 时回落正宫，保证全拼模式始终可用。
        InputMode.PINYIN -> RimeTextEngine(
            context,
            if (FuzzyPinyin.on(context) &&
                com.feelime.ime.engine.EngineDataStore.isFuzzySchemaReady(context)
            ) {
                FuzzyPinyin.SCHEMA_ID
            } else {
                "luna_pinyin"
            },
        )
        InputMode.DOUBLE_PINYIN -> RimeTextEngine(context, DoublePinyinScheme.schemaId(context))
        InputMode.FRENCH -> HunspellTextEngine(context, "fr", "bonjour")
        InputMode.RUSSIAN -> HunspellTextEngine(context, "ru_RU", "ёлка")
        InputMode.JAPANESE -> MozcTextEngine(context)
    }
}
