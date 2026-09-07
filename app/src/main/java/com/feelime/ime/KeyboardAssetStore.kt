package com.feelime.ime

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Serves the currently active keyboard (built-in or hot-update version) from
 * the synthetic HTTPS origin. Source selection lives in KeyboardStore.
 */
class KeyboardAssetStore(private val root: File) {

    fun response(path: String): WebResourceResponse {
        val relative = path.removePrefix("/keyboard/").ifBlank { "index.html" }
        if (relative.split('/').any { it == ".." || it.isBlank() }) return missing()
        if (relative !in SERVED_FILES) return missing()
        val file = File(root, relative)
        if (!file.isFile || !file.canonicalPath.startsWith(root.canonicalPath + File.separator)) return missing()
        val headers = mutableMapOf("Cache-Control" to "no-store")
        if (relative == "index.html") {
            headers["Content-Security-Policy"] =
                "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                    "connect-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'"
        }
        return WebResourceResponse(
            mimeType(relative),
            "UTF-8",
            200,
            "OK",
            headers,
            file.inputStream(),
        )
    }

    companion object {
        private val SERVED_FILES = setOf("index.html", "keyboard.css", "keyboard.js", "VERSION")

        private fun missing() = WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream("Not found".toByteArray()),
        )

        private fun mimeType(path: String) = when (path.substringAfterLast('.', "")) {
            "html" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            else -> "application/octet-stream"
        }
    }
}
