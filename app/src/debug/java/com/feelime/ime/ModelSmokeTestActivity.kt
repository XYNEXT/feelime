package com.feelime.ime

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File

/** Debug-only entry point used by adb to validate the packaged final-pass model. */
class ModelSmokeTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread({ runTest() }, "feelime-model-smoke-test").start()
    }

    private fun runTest() {
        val lines = ArrayList<String>()
        runCatching {
            val wave = WaveReader.readWave(File(filesDir, TEST_WAVE).absolutePath)
            check(wave.sampleRate == 16_000) { "Expected 16 kHz, got ${wave.sampleRate}" }
            FinalPassRecognizer(applicationContext).let { recognizer ->
                try {
                    val result = recognizer.recognize(wave.samples)
                    lines += "final-pass elapsedMs=${result.elapsedMs} text=[${result.text}]"
                } finally {
                    recognizer.release()
                }
            }
            // Thin builds load the streaming model from the
            // downloaded copy - construct and decode one wave through the SAME
            // ModelStore resolution AsrEngine uses.
            val store = ModelStore(applicationContext)
            val source = checkNotNull(store.sourceFor("asr-streaming")) {
                "streaming model not installed (thin build)"
            }
            OnlineRecognizer(modelAssetManager(applicationContext, source), streamingRecognizerConfig(source, AsrHotwords.vocabPath(applicationContext, source))).let {
                try {
                    // Feed in 100 ms chunks exactly like AsrEngine.recordLoop.
                    val stream = it.createStream("")
                    var offset = 0
                    while (offset < wave.samples.size) {
                        val end = minOf(offset + SAMPLE_RATE_ASR / 10, wave.samples.size)
                        val chunk = wave.samples.copyOfRange(offset, end)
                        stream.acceptWaveform(chunk, SAMPLE_RATE_ASR)
                        while (it.isReady(stream)) it.decode(stream)
                        offset = end
                    }
                    stream.inputFinished()
                    while (it.isReady(stream)) it.decode(stream)
                    val text = it.getResult(stream).text
                    lines += "streaming mode=${source.javaClass.simpleName} text=[$text]"
                } finally {
                    it.release()
                }
            }
        }.onSuccess {
            val message = lines.joinToString("\n")
            File(filesDir, RESULT_FILE).writeText(message)
            Log.i(TAG, message)
        }.onFailure { error ->
            val message = "ERROR ${error.stackTraceToString()}"
            File(filesDir, RESULT_FILE).writeText(message)
            Log.e(TAG, "Model smoke test failed", error)
        }
        finish()
    }

    private companion object {
        const val TAG = "FeelimeModelTest"
        const val TEST_WAVE = "asr-smoke.wav"
        const val RESULT_FILE = "asr-smoke-result.txt"
    }
}
