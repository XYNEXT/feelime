package com.feelime.ime.engine

import android.content.Context

/**
 * 全拼模糊音开关（issue #2）。原生持有选择（feelime_engine 偏好）：
 * 开启时全拼模式改用预编译的 luna_pinyin_fuzzy schema（平翘舌 z/zh c/ch
 * s/sh、声母 n/l、前后鼻音 an/ang en/eng in/ing 双向派生，prism 与全拼
 * 正宫独立命名，见 docs/design/double-pinyin.md §2.2 同一构建链）。
 * 关闭即回到 luna_pinyin；未知/损坏偏好一律视为关闭。
 */
object FuzzyPinyin {
    const val PREF_FILE = "feelime_engine"
    const val PREF_KEY = "fuzzy_pinyin"
    const val SCHEMA_ID = "luna_pinyin_fuzzy"

    fun on(context: Context): Boolean =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, false)

    fun set(context: Context, value: Boolean) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY, value).apply()
    }
}
