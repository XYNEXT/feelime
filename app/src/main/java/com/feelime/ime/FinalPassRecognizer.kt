package com.feelime.ime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/** Re-decodes a complete utterance with a stronger bilingual Paraformer.
 *  Model source (design §12.3): the model comes from APK assets (full builds) or the
 *  verified filesDir download (thin builds); with neither present the
 *  constructor stays a no-op and recognize() reports the model as missing -
 *  constructing sherpa against missing files would exit(-1) the process
 *  ( 教训). */
class FinalPassRecognizer(
    context: Context,
    modelStore: ModelStore? = null,
    source: ModelSource? = (modelStore ?: ModelStore(context)).sourceFor("asr-final"),
) {

    private val recognizer: OfflineRecognizer? = source?.let {
        OfflineRecognizer(
            if (it is ModelSource.Assets) context.assets else null,
            OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig(
                        model = it.pathFor("final-model/model.int8.onnx"),
                    ),
                    tokens = it.pathFor("final-model/tokens.txt"),
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "paraformer",
                ),
            ),
        )
    }

    fun recognize(samples: FloatArray, hotwords: String = ""): Result {
        val active = checkNotNull(recognizer) { "整句纠错模型未安装" }
        if (samples.isEmpty()) return Result("", 0)
        val started = SystemClock.elapsedRealtime()
        // Hotwords are best-effort here - a paraformer without
        // contextual support ignores (or rejects) them; reject falls back.
        val stream = if (hotwords.isEmpty()) {
            active.createStream()
        } else {
            runCatching { active.createStream(hotwords) }
                .onFailure { Log.w(TAG, "Final-pass hotwords unsupported; using plain stream", it) }
                .getOrElse { active.createStream() }
        }
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            active.decode(stream)
            Result(
                text = active.getResult(stream).text.trim(),
                elapsedMs = SystemClock.elapsedRealtime() - started,
            )
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer?.release()

    data class Result(val text: String, val elapsedMs: Long)

    private companion object {
        const val TAG = "FeelimeFinalPass"
        const val SAMPLE_RATE = 16_000
    }
}
