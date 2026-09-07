package com.feelime.ime

/** 语音识别设置（feelime_asr prefs）： 的两个用户可调项。 */
object AsrSettings {
    const val PREFS = "feelime_asr"
    /** true = 语音结果剥掉句尾句号（默认， 行为）。 */
    const val KEY_STRIP_FINAL_PERIOD = "strip_final_period"
    /** 热词表，一行一个。 */
    const val KEY_HOTWORDS = "hotwords"
}
