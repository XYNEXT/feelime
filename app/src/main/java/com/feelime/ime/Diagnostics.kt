package com.feelime.ime

import android.content.Context
import android.os.SystemClock

/**
 * 诊断记录（用户报告：双拼下按键字母直接上屏，A/B 聊天窗口可复现）。
 *
 * 开关打开后，IME 把引擎降级链路的关键事件收进内存环形缓冲，供设置页
 * 一键导出做故障分析。采集点（全部不含文本内容）：
 *   - 编辑器切换：包名 / inputType hex / imeOptions hex / 敏感与终端标记
 *   - 引擎生命周期：startEngine 目标与降级前状态、READY、工厂失败、
 *     warmup 超时、队列溢出、降级期按键直出重放计数
 *   - 徽标清除路径：endVoiceSession / recreateBegin / clearDegrade
 *   - 用户动作：selectMode
 *
 * 默认关闭；关闭时 log() 直接返回（调用方不做判断也近乎零开销）。
 * 环形缓冲 400 条、单条截断 220 字符，只进内存，不落盘、不进备份。
 */
object Diagnostics {
    private const val PREFS = "feelime_diagnostics"
    private const val KEY_ENABLED = "enabled"
    private const val MAX_EVENTS = 400
    private const val MAX_EVENT_CHARS = 220

    private val lock = Any()
    private val events = ArrayDeque<String>()
    private var recording = false
    private var startedAt = 0L
    private var seq = 0L

    /** 当前引擎/编辑器状态行（导出头用），由 service 在 hello 推送时刷新。 */
    @Volatile
    var liveState: String = ""
        private set

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /** 进程启动时恢复记录状态（service onCreate 调用）。 */
    fun refresh(context: Context) {
        synchronized(lock) {
            recording = enabled(context)
            if (recording && startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        }
    }

    fun setEnabled(context: Context, on: Boolean) {
        synchronized(lock) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, on).apply()
            recording = on
            events.clear()
            seq = 0
            startedAt = if (on) SystemClock.elapsedRealtime() else 0L
        }
        if (on) log("diag", "recording started")
    }

    fun log(tag: String, detail: String = "") {
        synchronized(lock) {
            if (!recording) return
            seq += 1
            val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
            var line = String.format(java.util.Locale.US, "%.3f #%d %s %s",
                seconds, seq, tag, detail).trim()
            if (line.length > MAX_EVENT_CHARS) line = line.take(MAX_EVENT_CHARS - 1) + "…"
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(line)
        }
    }

    /** service 刷新导出头里的实时状态（mode / degrade / 最后编辑器）。 */
    fun noteLiveState(state: String) {
        liveState = state
    }

    fun snapshot(): List<String> = synchronized(lock) { events.toList() }
}
