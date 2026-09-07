package com.feelime.ime

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Raw phrases for sherpa's cjkchar+bpe encoder; never pre-tokenize twice. */
object AsrHotwords {
    const val MAX_LINES = 50
    const val MAX_CHARS_PER_LINE = 30
    internal const val BPE_ASSET = "asr-hotwords/bpe.vocab"
    internal const val BPE_SHA256 = "d0b642f3a2eacd5fadefdeff9e0e1358cab729647cbb7fe58cf738e1f7407029"
    internal const val TOKENS_SHA256 = "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3"

    fun load(context: Context): List<String> =
        context.getSharedPreferences("feelime_asr", Context.MODE_PRIVATE)
            .getString("hotwords", null).orEmpty().lines()
            .map(String::trim).filter(String::isNotEmpty).distinct()
            .take(MAX_LINES).filter { it.length <= MAX_CHARS_PER_LINE }

    /** JNI can exit the process on invalid configuration. Verify both files
     * before its constructor; the small BPE resource is in full AND thin APKs. */
    fun vocabPath(context: Context, source: ModelSource): String {
        val bpe = context.assets.open(BPE_ASSET).use { it.readBytes() }
        val tokens = readTokens(context, source)
        verifyResources(bpe, tokens)
        if (source is ModelSource.Assets) return BPE_ASSET
        val target = File(context.filesDir, BPE_ASSET)
        target.parentFile!!.mkdirs()
        if (!target.isFile || sha256(target.readBytes()) != BPE_SHA256) {
            val temporary = File(target.parentFile, "bpe.vocab.tmp")
            temporary.writeBytes(bpe)
            check(temporary.renameTo(target)) { "无法准备语音热词词表" }
        }
        return target.absolutePath
    }

    internal fun verifyResources(bpe: ByteArray, tokens: ByteArray) {
        check(sha256(bpe) == BPE_SHA256) { "语音热词词表校验失败，请重新安装应用" }
        check(sha256(tokens) == TOKENS_SHA256) { "语音模型与热词词表不匹配，请重新下载语音模型" }
    }

    fun encode(context: Context, source: ModelSource, words: List<String> = load(context)): String {
        if (words.isEmpty()) return ""
        val tokens = readTokens(context, source)
        check(sha256(tokens) == TOKENS_SHA256) { "语音模型与热词词表不匹配，请重新下载语音模型" }
        val vocab = tokens.decodeToString().lineSequence()
            .map { it.substringBefore(' ') }.toSet()
        return encodeWords(words, vocab)
    }

    /** CreateStream replaces '/' with a newline. Passing '/#/' adds a bogus
     * '#' phrase. Colons inject boost scores, so reject protocol syntax and
     * unsupported glyphs here rather than allowing native parsing failures. */
    internal fun encodeWords(words: List<String>, vocab: Set<String>): String {
        require(words.size <= MAX_LINES) { "热词最多 $MAX_LINES 行" }
        return words.mapIndexed { index, value ->
            val word = value.trim().replace(Regex("\\s+"), " ").uppercase(Locale.ROOT)
            require(word.isNotEmpty() && word.length <= MAX_CHARS_PER_LINE) {
                "第 ${index + 1} 行热词为空或过长"
            }
            require(value.none { it == '/' || it == '#' || it == ':' || it == '<' || it == '>' || it.isISOControl() }) {
                "第 ${index + 1} 行热词含不支持的分隔符，请只填写词语"
            }
            require(word.codePoints().toArray().all { point ->
                val glyph = String(Character.toChars(point))
                point == 32 || glyph in vocab || "▁$glyph" in vocab
            }) { "第 ${index + 1} 行热词含语音模型不支持的字符" }
            word
        }.distinct().joinToString("/")
    }

    /** Keep final-pass correction unless it removes a configured hotword
     * actually recognized in the stream. Match whole Latin words; tolerate
     * capitalization and model-inserted whitespace, including within Chinese. */
    internal fun selectFinalText(streaming: String, final: String, hotwords: String): String {
        if (final.isBlank()) return streaming
        val lostHotword = hotwords.split('/').filter(String::isNotBlank).any { phrase ->
            val glyphs = phrase.filterNot(Char::isWhitespace).codePoints().toArray()
            if (glyphs.isEmpty()) return@any false
            val pattern = buildString {
                if (isLatinWordPoint(glyphs.first())) append("(?<![A-Za-z0-9])")
                append(glyphs.joinToString("\\s*") { Regex.escape(String(Character.toChars(it))) })
                if (isLatinWordPoint(glyphs.last())) append("(?![A-Za-z0-9])")
            }
            val matcher = Regex(pattern, RegexOption.IGNORE_CASE)
            matcher.containsMatchIn(streaming) && !matcher.containsMatchIn(final)
        }
        return if (lostHotword) streaming else final
    }

    private fun isLatinWordPoint(point: Int): Boolean =
        point in 'A'.code..'Z'.code || point in 'a'.code..'z'.code || point in '0'.code..'9'.code

    private fun readTokens(context: Context, source: ModelSource): ByteArray =
        if (source is ModelSource.Assets) context.assets.open("asr-model/tokens.txt").use { it.readBytes() }
        else File(source.pathFor("asr-model/tokens.txt")).readBytes()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
