package com.feelime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class AsrHotwordsTest {
    private val tokens = File("src/modelAssets/full/asr-model/tokens.txt").readBytes()
    private val bpe = File("src/main/assets/asr-hotwords/bpe.vocab").readBytes()
    private val vocab = tokens.decodeToString().lines().map { it.substringBefore(' ') }.toSet()

    @Test fun rawMixedPhrasesReachNativeWithoutDoubleEncoding() {
        assertEquals("你好/HELLO/NEW YORK/GPU模式",
            AsrHotwords.encodeWords(listOf("你好", "hello", "New   York", "GPU模式"), vocab))
        assertEquals("", AsrHotwords.encodeWords(emptyList(), vocab))
        assertEquals("HELLO", AsrHotwords.encodeWords(listOf("hello", "HELLO"), vocab))
    }

    @Test fun unsupportedInputCannotEnterNativeProtocolParser() {
        for (word in listOf("你好/世界", "hello :99", "hello/#/world", "a\nb", "<unk>", "⌘⌥", "😀")) {
            assertThrows(IllegalArgumentException::class.java) { AsrHotwords.encodeWords(listOf(word), vocab) }
        }
    }

    @Test fun actualVocabularyAndShippedTokenizerMustMatchPinnedModel() {
        AsrHotwords.verifyResources(bpe, tokens)
        assertThrows(IllegalStateException::class.java) { AsrHotwords.verifyResources(bpe + 0, tokens) }
        assertThrows(IllegalStateException::class.java) { AsrHotwords.verifyResources(bpe, tokens + 0) }
    }

    @Test fun finalCorrectionIsPreservedWhenNoRecognizedHotwordIsLost() {
        assertEquals("你好，世界", AsrHotwords.selectFinalText("你好世介", "你好，世界", ""))
        assertEquals("你好，世界", AsrHotwords.selectFinalText("你好世介", "你好，世界", "微软"))
        assertEquals("New York is nice.", AsrHotwords.selectFinalText("NEWYORK IS NICE", "New York is nice.", "NEW YORK"))
        assertEquals("使用语音识别。", AsrHotwords.selectFinalText("使用语 音识别", "使用语音识别。", "语音识别"))
    }

    @Test fun recognizedHotwordSurvivesDestructiveFinalPass() {
        assertEquals("今天是礼拜二", AsrHotwords.selectFinalText("今天是礼拜二", "今天是李白二", "礼拜"))
        assertEquals("NEW YORK IS NICE", AsrHotwords.selectFinalText("NEW YORK IS NICE", "New work is nice.", "NEW YORK"))
        assertEquals("你好", AsrHotwords.selectFinalText("你好", "", "你好"))
    }

    @Test fun latinWordFragmentsDoNotSuppressFinalCorrection() {
        assertEquals("The cathedral is old.", AsrHotwords.selectFinalText("THE CATHEDRAL IS OLD", "The cathedral is old.", "CAT"))
        assertEquals("Categorize this.", AsrHotwords.selectFinalText("CATALOG THIS", "Categorize this.", "CAT"))
    }
}
