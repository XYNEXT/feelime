package com.feelime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishTextNormalizerTest {
    @Test
    fun restoresCommonEnglishContractions() {
        assertEquals("what's your name", EnglishTextNormalizer.normalize("WHATS YOUR NAME"))
        assertEquals(
            "i'm fine and i don't know",
            EnglishTextNormalizer.normalize("I M FINE AND I DONT KNOW"),
        )
    }

    @Test
    fun preservesChineseWhileNormalizingEnglish() {
        assertEquals(
            "今天是 monday tomorrow 是星期二",
            EnglishTextNormalizer.normalize("今天是 MONDAY TOMORROW 是星期二"),
        )
    }

    @Test
    fun keepsQuestionMarksButNeverEndsWithAPeriod() {
        // 语音结果默认不加句号收尾；标点模型给的句尾句号也剥掉。
        assertEquals("what's your name?", EnglishTextNormalizer.ensureTerminalPunctuation("what's your name"))
        assertEquals("hello world", EnglishTextNormalizer.ensureTerminalPunctuation("hello world"))
        assertEquals("hello world", EnglishTextNormalizer.ensureTerminalPunctuation("hello world."))
        assertEquals("今天天气不错", EnglishTextNormalizer.ensureTerminalPunctuation("今天天气不错。"))
        assertEquals("ok", EnglishTextNormalizer.ensureTerminalPunctuation("ok ."))
        assertEquals("你叫什么名字吗？", EnglishTextNormalizer.ensureTerminalPunctuation("你叫什么名字吗"))
        // 问号/叹号/省略号自带语义，保留。
        assertEquals("真的吗？", EnglishTextNormalizer.ensureTerminalPunctuation("真的吗？"))
        assertEquals("等等…", EnglishTextNormalizer.ensureTerminalPunctuation("等等…"))
    }
}
