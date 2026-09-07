package com.feelime.ime.spike

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Isolated harness. It deliberately does not depend on Feelime production
 * classes, so collecting a baseline cannot change the production source set.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "Running deterministic PCM baseline…"
            textSize = 16f
            setPadding(32, 48, 32, 32)
        }
        setContentView(status)
        Thread({ runBaseline() }, "feelime-asr-baseline").start()
    }

    private fun runBaseline() {
        val result = runCatching { measure() }.getOrElse { error ->
            JSONObject()
                .put("status", "ERROR")
                .put("error", error.stackTraceToString())
        }
        File(filesDir, RESULT_FILE).writeText(result.toString(2))
        runOnUiThread { status.text = result.toString(2) }
    }

    private fun measure(): JSONObject {
        val fixture = File(filesDir, FIXTURE_FILE)
        check(fixture.isFile) { "Push the WAV to files/$FIXTURE_FILE first" }
        val wave = WaveReader.readWave(fixture.absolutePath)
        check(wave.sampleRate == SAMPLE_RATE) { "Expected 16 kHz, got ${wave.sampleRate}" }

        val loadStarted = SystemClock.elapsedRealtime()
        val online = createOnline()
        val offline = createOffline()
        val punctuation = createPunctuation()
        val modelLoadMs = SystemClock.elapsedRealtime() - loadStarted

        try {
            val stream = online.createStream()
            var firstPartialMs: Long? = null
            var rawPartial = ""
            val utterance = FloatAccumulator()
            val finalSegments = mutableListOf<String>()
            val events = JSONArray()
            var endpointCount = 0
            var lastFinalAt: Long? = null
            val feedStarted = SystemClock.elapsedRealtime()
            fun emitPartial(text: String) {
                if (text.isNotBlank() && firstPartialMs == null) {
                    firstPartialMs = SystemClock.elapsedRealtime() - feedStarted
                }
                events.put(JSONObject().put("type", "partial").put("text", text))
            }
            fun emitFinal(text: String) {
                finalSegments += text
                lastFinalAt = SystemClock.elapsedRealtime()
                events.put(JSONObject().put("type", "final").put("text", text))
            }
            try {
                var offset = 0
                while (offset < wave.samples.size) {
                    val end = minOf(offset + CHUNK_SAMPLES, wave.samples.size)
                    val chunk = wave.samples.copyOfRange(offset, end)
                    utterance.append(chunk)
                    stream.acceptWaveform(chunk, SAMPLE_RATE)
                    while (online.isReady(stream)) online.decode(stream)
                    rawPartial = online.getResult(stream).text.trim()
                    emitPartial(EnglishTextNormalizer.normalize(rawPartial))
                    if (online.isEndpoint(stream)) {
                        endpointCount += 1
                        val endpointSamples = utterance.takeAndClear()
                        online.reset(stream)
                        val refined = refine(offline, endpointSamples, rawPartial)
                        val finalText = postProcess(punctuation, refined)
                        if (finalText.isNotBlank()) emitFinal(finalText)
                        rawPartial = ""
                        emitPartial("")
                    }
                    offset = end
                }
                val stopStarted = SystemClock.elapsedRealtime()
                stream.inputFinished()
                while (online.isReady(stream)) online.decode(stream)
                val tail = online.getResult(stream).text.trim()
                val streamingTail = tail.ifBlank { rawPartial }
                val refinedTail = refine(offline, utterance.takeAndClear(), streamingTail)
                val tailText = postProcess(punctuation, refinedTail)
                if (tailText.isNotBlank()) emitFinal(tailText)
                emitPartial("")
                events.put(JSONObject().put("type", "stopped"))
                val stoppedAt = SystemClock.elapsedRealtime()
                val stopToFinalMs = lastFinalAt?.takeIf { it >= stopStarted }?.minus(stopStarted)
                val stopToStoppedMs = stoppedAt - stopStarted
                val totalMs = stoppedAt - feedStarted
                val finalText = finalSegments.joinToString("")

                return JSONObject()
                    .put("status", "OK")
                    .put("fixture", FIXTURE_FILE)
                    .put("sampleRate", wave.sampleRate)
                    .put("sampleCount", wave.samples.size)
                    .put("modelLoadMs", modelLoadMs)
                    .put("firstPartialFromFirstPcmMs", firstPartialMs ?: JSONObject.NULL)
                    .put("stopToFinalCallbackMs", stopToFinalMs ?: JSONObject.NULL)
                    .put("stopToStoppedCallbackMs", stopToStoppedMs)
                    .put("firstPcmToStoppedCallbackMs", totalMs)
                    .put("endpointCount", endpointCount)
                    .put("finalSegments", JSONArray(finalSegments))
                    .put("finalText", finalText)
                    .put("keyTokens", JSONArray(keyTokens(finalText)))
                    .put("events", events)
            } finally {
                stream.release()
            }
        } finally {
            punctuation.release()
            offline.release()
            online.release()
        }
    }

    private fun createOnline() = OnlineRecognizer(
        assets,
        OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "asr-model/encoder.int8.onnx",
                    decoder = "asr-model/decoder.onnx",
                    joiner = "asr-model/joiner.int8.onnx",
                ),
                tokens = "asr-model/tokens.txt",
                numThreads = 1,
                provider = "cpu",
                modelType = "zipformer",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 1.2f, 0f),
                rule3 = EndpointRule(false, 0f, 20f),
            ),
            enableEndpoint = true,
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
        ),
    )

    private fun createOffline() = OfflineRecognizer(
        assets,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = "final-model/model.int8.onnx"),
                tokens = "final-model/tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "paraformer",
            ),
        ),
    )

    private fun createPunctuation() = OfflinePunctuation(
        assets,
        OfflinePunctuationConfig(
            OfflinePunctuationModelConfig(
                ctTransformer = "punctuation/model.int8.onnx",
                numThreads = 1,
                provider = "cpu",
            ),
        ),
    )

    private fun refine(offline: OfflineRecognizer, samples: FloatArray, streamingText: String): String {
        if (streamingText.isBlank()) return ""
        if (samples.size < MIN_FINAL_PASS_SAMPLES) return streamingText
        return runCatching {
            val stream = offline.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                offline.decode(stream)
                offline.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: streamingText
    }

    private fun postProcess(punctuation: OfflinePunctuation, text: String): String {
        val normalized = EnglishTextNormalizer.normalize(text)
        if (normalized.isBlank()) return ""
        val restored = runCatching { punctuation.addPunctuation(normalized).trim() }
            .getOrDefault(normalized).ifBlank { normalized }
        return EnglishTextNormalizer.ensureTerminalPunctuation(restored)
    }

    private fun keyTokens(value: String) = listOf(
        "monday",
        "today",
        "the day after tomorrow",
        "星期三",
        "what's",
        "name",
    ).filter { value.contains(it, ignoreCase = true) }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 1_600
        const val MIN_FINAL_PASS_SAMPLES = SAMPLE_RATE / 4
        const val FIXTURE_FILE = "input.wav"
        const val RESULT_FILE = "baseline-result.json"
    }
}

private class FloatAccumulator {
    private val chunks = mutableListOf<FloatArray>()
    private var size = 0

    fun append(value: FloatArray) {
        chunks += value
        size += value.size
    }

    fun takeAndClear(): FloatArray {
        val output = FloatArray(size)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(output, offset)
            offset += chunk.size
        }
        chunks.clear()
        size = 0
        return output
    }
}

/** Exact copy of production EnglishTextNormalizer. */
private object EnglishTextNormalizer {
    private val spacedSuffix = Regex(
        "\\b(i|you|we|they|he|she|it|what|that|there|here|who|where|when|why|how)\\s+" +
            "(m|re|ve|ll|d|s)\\b",
    )
    private val spacedNegative = Regex("\\b(can|could|would|should|do|does|did|is|are|was|were|have|has|had|wo)\\s+t\\b")
    private val bareContractions = linkedMapOf(
        "whats" to "what's", "thats" to "that's", "theres" to "there's",
        "heres" to "here's", "whos" to "who's", "wheres" to "where's",
        "hows" to "how's", "im" to "i'm", "youre" to "you're",
        "youve" to "you've", "youll" to "you'll",
        "weve" to "we've", "theyre" to "they're", "theyve" to "they've",
        "hes" to "he's", "shes" to "she's", "dont" to "don't",
        "doesnt" to "doesn't", "didnt" to "didn't", "cant" to "can't",
        "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
        "wont" to "won't", "isnt" to "isn't", "arent" to "aren't",
        "wasnt" to "wasn't", "werent" to "weren't", "havent" to "haven't",
        "hasnt" to "hasn't", "hadnt" to "hadn't",
    )
    private val terminalPunctuation = Regex("[.!?。！？…]$")
    private val englishQuestion = Regex(
        "^(what|who|whose|where|when|why|how|is|are|am|was|were|do|does|did|" +
            "can|could|would|will|should|have|has|had)\\b",
    )

    fun normalize(text: String): String {
        var value = text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ENGLISH)
        value = spacedSuffix.replace(value) { "${it.groupValues[1]}'${it.groupValues[2]}" }
        value = spacedNegative.replace(value) {
            val stem = it.groupValues[1]
            if (stem == "wo") "won't" else "${stem}n't"
        }
        bareContractions.forEach { (plain, contraction) ->
            if (plain != contraction) value = Regex("\\b$plain\\b").replace(value, contraction)
        }
        return value
    }

    fun ensureTerminalPunctuation(text: String): String {
        val clean = text.trim()
        if (clean.isEmpty() || terminalPunctuation.containsMatchIn(clean)) return clean
        val lower = clean.lowercase(Locale.ENGLISH)
        return when {
            clean.last() in "吗呢么" -> "$clean？"
            englishQuestion.containsMatchIn(lower) -> "$clean?"
            clean.count(Char::isLetter) >= 3 -> clean + if (clean.last().isAsciiLetter()) "." else "。"
            else -> clean
        }
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
}
