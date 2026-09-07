package com.feelime.ime.nativeengine

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Runs Mozc cold-failure probes in a dedicated process.  Upstream
 * onPostLoad falls back to a minimal engine for unusable data and its
 * process-global session handler can never be reset, so probing must not
 * share a process with a successful data load.
 */
class MozcColdProbeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thread(name = "feelime-mozc-cold-probe") {
            val rendered = runCatching {
                MozcColdProbeSmoke.run(File(filesDir, "engine-data"), filesDir)
            }.map { it.toString(2) }
                .getOrElse { throwable ->
                    JSONObject()
                        .put("status", "failed")
                        .put("error", throwable.stackTraceToString())
                        .toString(2)
                }
            File(filesDir, "mozc-cold-probe.json").writeText(rendered)
            finish()
        }
    }
}
