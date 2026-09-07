package com.feelime.ime.nativeengine

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val webReady = CountDownLatch(1)
    private var webStartNanos = 0L
    @Volatile private var webReadyNanos = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { text = "Running native engine smoke…" }
        if (intent.getStringExtra("benchmark") != null) {
            webStartNanos = SystemClock.elapsedRealtimeNanos()
            setContentView(WebView(this).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        webReadyNanos = SystemClock.elapsedRealtimeNanos()
                        webReady.countDown()
                    }
                }
                loadUrl("file:///android_asset/keyboard/index.html")
            })
        } else {
            setContentView(ScrollView(this).apply { addView(text) })
        }
        thread(name = "feelime-native-smoke") {
            val rendered = runCatching {
                val benchmark = intent.getStringExtra("benchmark")
                if (benchmark == null) runSmoke() else runExternalBenchmark(benchmark)
            }.map { it.toString(2) }
                .getOrElse { throwable ->
                    JSONObject()
                        .put("status", "failed")
                        .put("error", throwable.stackTraceToString())
                        .toString(2)
                }
            File(filesDir, "native-engine-smoke.json").writeText(rendered)
            Log.i("FeelimeNativeSmoke", rendered)
            runOnUiThread { text.text = rendered }
        }
    }

    private fun runSmoke(): JSONObject {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val data = File(filesDir, "engine-data")
        ensureEngineData(data)
        val assetsReadyAt = SystemClock.elapsedRealtimeNanos()
        val basePssKiB = Debug.getPss()
        val rimeUser = File(filesDir, "rime-user").apply { mkdirs() }
        val mozcUser = File(filesDir, "mozc-user").apply { mkdirs() }

        // Failure probes are intentionally the first native engine calls in a
        // fresh process. Rime and Mozc retain process-global state, so running
        // a successful smoke first would make missing/corrupt-data probes
        // incapable of proving cold-start failure behavior.  Mozc additionally
        // cannot recover in-process after a failed load (upstream falls back to
        // a minimal engine and keeps it forever), so its cold probes run in a
        // dedicated process before this process performs any successful load.
        val mozcProbeResult = File(filesDir, "mozc-cold-probe.json")
        mozcProbeResult.delete()
        startActivity(android.content.Intent(this, MozcColdProbeActivity::class.java))
        val mozcProbeDeadline = SystemClock.elapsedRealtime() + 90_000
        while (!mozcProbeResult.isFile && SystemClock.elapsedRealtime() < mozcProbeDeadline) {
            Thread.sleep(500)
        }
        check(mozcProbeResult.isFile) { "Mozc cold-probe process produced no report" }
        val mozcColdProbe = JSONObject(mozcProbeResult.readText())
        check(mozcColdProbe.getString("status") == "passed") {
            "Mozc cold-probe process failed: $mozcColdProbe"
        }

        val nativeContract = NativeContractSmoke.run(
            data, filesDir,
            mozcColdProbe.getJSONArray("reports").getJSONObject(0).getJSONArray("failureProbes"),
        )
        check(nativeContract.getString("status") == "passed")
        for (index in 0 until nativeContract.getJSONArray("reports").length()) {
            val report = nativeContract.getJSONArray("reports").getJSONObject(index)
            check(report.getBoolean("strictlyIncreasingRevisions")) { "bad contract revisions: $report" }
            check(report.getBoolean("lateEventDropped")) { "late event was accepted: $report" }
            val events = report.getJSONArray("events")
            check((0 until events.length()).map(events::getJSONObject).any {
                it.optString("code") == "PAGE_BOUNDARY" && !it.getBoolean("consumed")
            }) { "non-consumed page boundary was not observed: $report" }
        }

        val rimeStartedAt = SystemClock.elapsedRealtimeNanos()
        val rime = JSONObject(NativeSmoke.runRime(File(data, "rime").path, rimeUser.path))
        val rimeFinishedAt = SystemClock.elapsedRealtimeNanos()
        val afterRimePssKiB = Debug.getPss()
        check(rime.getString("commit").contains("你好")) { "Rime failed nihao -> 你好: $rime" }

        val hunspellStartedAt = SystemClock.elapsedRealtimeNanos()
        val hunspell = JSONObject(
            NativeSmoke.runHunspell(
                File(data, "hunspell/fr.aff").path,
                File(data, "hunspell/fr.dic").path,
                File(data, "hunspell/ru_RU.aff").path,
                File(data, "hunspell/ru_RU.dic").path,
            ),
        )
        val hunspellFinishedAt = SystemClock.elapsedRealtimeNanos()
        val afterHunspellPssKiB = Debug.getPss()
        check(hunspell.getString("frSuggestions").lineSequence().any { it == "bonjour" }) {
            "Hunspell French correction failed: $hunspell"
        }
        check(hunspell.getString("ruSuggestions").lineSequence().any { it == "привет" }) {
            "Hunspell Russian correction failed: $hunspell"
        }

        val mozcStartedAt = SystemClock.elapsedRealtimeNanos()
        val mozc = MozcSmoke.run(mozcUser.path, File(data, "mozc/mozc.data").path)
        val mozcFinishedAt = SystemClock.elapsedRealtimeNanos()
        val afterMozcPssKiB = Debug.getPss()
        val mozcText = mozc.toString()
        val kanjiCase = mozc.getJSONArray("cases").getJSONObject(0)
        check(kanjiCase.getString("selectedCandidate").any {
            it.code in 0x3400..0x9fff
        }) { "Mozc candidate selection failed: $mozc" }
        check(kanjiCase.getJSONObject("commitOutput").getString("result") == "今日は") {
            "Mozc selected candidate was not committed: $mozc"
        }
        check(mozc.getJSONObject("undo").optInt("deletionLength") > 0) {
            "Mozc undo did not request deletion of the committed text: $mozc"
        }
        val cases = mozc.getJSONArray("cases")
        fun case(input: String): JSONObject = (0 until cases.length())
            .map(cases::getJSONObject)
            .first { it.getString("input") == input }
        check(case("nna").getString("preedit") == "んな") { "Mozc adapter n/nn handling failed: $mozc" }
        check(case("gakkou").getString("preedit") == "がっこう") { "Mozc small-tsu preedit failed: $mozc" }
        check(case("n'").getString("preedit") == "ん") { "Mozc apostrophe n handling failed: $mozc" }
        check("コーヒー" in case("ko-hi-").getJSONArray("candidates").toString()) {
            "Mozc long-vowel conversion failed: $mozc"
        }
        check(case(".").getString("preedit") == "。" && case(",").getString("preedit") == "、") {
            "Mozc Japanese punctuation failed: $mozc"
        }
        check(case("kanji").getJSONObject("commitOutput").getString("result") == "漢字") {
            "Mozc kanji conversion/selection failed: $mozc"
        }
        val backspace = mozc.getJSONObject("conversionBackspace")
        check(backspace.getJSONObject("reverted").getString("preedit") == "かんじ") {
            "Mozc conversion backspace failed: $mozc"
        }
        check(backspace.getJSONObject("adapterNormalizedShortened").getString("preedit") == "かん") {
            "Mozc composing backspace failed: $mozc"
        }

        check("ガッコウ" in mozcText || "学校" in mozcText) {
            "Mozc katakana/kanji conversion failed: $mozc"
        }
        val nativeLanguage = NativeLanguageSmoke.run(data, filesDir)
        val resourceBenchmark = NativeResourceBenchmark.run(data, filesDir)

        return JSONObject()
            .put("status", "passed")
            .put("pageSizeBytes", pageSize())
            .put("timingMs", JSONObject()
                .put("assetCopy", elapsedMs(startedAt, assetsReadyAt))
                .put("rime", elapsedMs(rimeStartedAt, rimeFinishedAt))
                .put("hunspell", elapsedMs(hunspellStartedAt, hunspellFinishedAt))
                .put("mozc", elapsedMs(mozcStartedAt, mozcFinishedAt)))
            .put("processPssKiB", Debug.getPss())
            .put("pssProfileKiB", JSONObject()
                .put("baseAfterAssetCopy", basePssKiB)
                .put("afterRimeFinalize", afterRimePssKiB)
                .put("afterHunspellDestroy", afterHunspellPssKiB)
                .put("afterMozcCases", afterMozcPssKiB)
                .put("engineDeltaFromBase", afterMozcPssKiB - basePssKiB))
            .put("rime", rime)
            .put("hunspell", hunspell)
            .put("mozc", mozc)
            .put("mozcColdProbe", mozcColdProbe)
            .put("nativeContract", nativeContract)
            .put("nativeLanguage", nativeLanguage)
            .put("resourceBenchmark", resourceBenchmark)
    }

    private fun runExternalBenchmark(operation: String): JSONObject {
        val processStartedAt = SystemClock.elapsedRealtimeNanos()
        check(webReady.await(10, TimeUnit.SECONDS)) { "benchmark WebView did not become ready" }
        val data = File(filesDir, "engine-data")
        val directReadyAt = SystemClock.elapsedRealtimeNanos()
        val provisionStartedAt = SystemClock.elapsedRealtimeNanos()
        val provisionedNow = ensureEngineData(data)
        val provisionReadyAt = SystemClock.elapsedRealtimeNanos()
        val engine = intent.getStringExtra("engine") ?: "rime"
        val asrLoaded = intent.getBooleanExtra("loadAsr", false)
        val asrMeasurement = if (asrLoaded) {
            val holder = Class.forName("com.feelime.ime.nativeengine.AsrMemoryHolder")
            holder.getMethod("load", android.content.Context::class.java).invoke(null, this) as JSONObject
        } else null
        val measurement = if (operation == "direct-idle") JSONObject().put("heldForPss", true)
            else NativeResourceBenchmark.runExternal(operation, engine, data, filesDir)
        return JSONObject().put("status", "passed").put("operation", operation).put("engine", engine)
            .put("pid", android.os.Process.myPid()).put("processStartedNanos", processStartedAt)
            .put("webViewStartNanos", webStartNanos).put("webViewReadyNanos", webReadyNanos)
            .put("webViewReadyMs", (webReadyNanos - webStartNanos) / 1_000_000.0)
            .put("directReadyNanos", directReadyAt).put("provisionStartedNanos", provisionStartedAt)
            .put("provisionReadyNanos", provisionReadyAt).put("provisionedNow", provisionedNow)
            .put("asrLoaded", asrLoaded).put("asrMeasurement", asrMeasurement ?: JSONObject.NULL)
            .put("measurement", measurement)
    }

    private fun ensureEngineData(destination: File): Boolean {
        val marker = File(destination, ".feelime-data-v2-ready")
        if (marker.isFile) return false
        val staging = File(filesDir, "engine-data.staging").apply { deleteRecursively(); mkdirs() }
        copyAssetTree("engine-data", staging)
        marker.parentFile?.mkdirs()
        if (destination.exists()) destination.deleteRecursively()
        check(staging.renameTo(destination))
        marker.writeText("ready\n")
        return true
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> destination.outputStream().use(input::copyTo) }
            return
        }
        destination.mkdirs()
        children.forEach { child -> copyAssetTree("$assetPath/$child", File(destination, child)) }
    }

    private external fun pageSize(): Long

    private fun elapsedMs(start: Long, end: Long): Double = (end - start) / 1_000_000.0

    companion object {
        init { System.loadLibrary("feelime_smoke") }
    }
}
