package com.feelime.ime.update

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.ssl.SSLException

interface UrlConnection {
    val responseCode: Int
    val location: String?
    fun body(): InputStream
    fun disconnect()
}

interface UrlConnectionFactory {
    @Throws(IOException::class)
    fun open(url: URI): UrlConnection
}

/** Production transport (https; plain http when the caller opts in
 * for debug intranet updates); never follows redirects internally.
 * Also carries PLAINTEXT http when the caller opts in (debug
 * builds only - the intranet iteration loop ships unsigned ZIPs over LAN).
 * Every fetch is uncached - the metainfo source MUST revalidate
 * (a cached metainfo would keep pointing at an old zip after a release),
 * and the one-shot zip download has no use for a cache either. */
class HttpUrlConnectionFactory(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : UrlConnectionFactory {
    override fun open(url: URI): UrlConnection {
        val connection = url.toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("Pragma", "no-cache")
        return object : UrlConnection {
            override val responseCode get() = connection.responseCode
            override val location: String? get() = connection.getHeaderField("Location")
            override fun body(): InputStream = connection.inputStream
            override fun disconnect() = connection.disconnect()
        }
    }
}

/** A stable update SOURCE. The user saves one metainfo.json
 * URL; every check re-downloads it (uncached) and reads the current zip
 * location, so a release never requires re-copying a new address.
 *
 * The parser is deliberately hand-rolled (no JSON dependency in unit
 * tests): metainfo.json is a flat object of string fields we define -
 * {"keyboard_version":"3.16.0","url":"https://host/feelime-keyboard.zip"}. */
data class UpdateMetainfo(
    /** The zip location (required for an install). */
    val url: String?,
    /** The packaged keyboard version (optional, display only). */
    val version: String?,
) {
    companion object {
        fun parse(text: String): UpdateMetainfo {
            var url: String? = null
            var version: String? = null
            for (key in listOf("url", "keyboard_version")) {
                // "key" : "value" - tolerate whitespace; values must not
                // span lines (metainfo fields are URLs and version strings).
                val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
                val value = regex.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                if (key == "url") url = value else version = value
            }
            return UpdateMetainfo(url, version)
        }
    }
}

class KeyboardUpdateDownloader(
    private val connections: UrlConnectionFactory,
    private val maxBytes: Long = 5L * 1024 * 1024,
    private val maxRedirects: Int = 3,
    /** Debug-only intranet updates: plain http is refused unless opted in. */
    private val allowHttp: Boolean = false,
) {
    sealed class Result {
        class Ok(val bytes: ByteArray, val effectiveUrl: URI) : Result()
        class Fail(val code: KeyboardUpdateErrorCode, val detail: String) : Result()
    }

    fun download(url: URI): Result {
        var current = url
        var redirects = 0
        while (true) {
            if (!schemeAllowed(current)) {
                return fail(KeyboardUpdateErrorCode.URL_NOT_HTTPS, sanitizeTarget(current))
            }
            if (current.rawUserInfo != null) {
                return fail(KeyboardUpdateErrorCode.URL_USER_INFO, redactUrl(current))
            }
            val connection = try {
                connections.open(current)
            } catch (_: SocketTimeoutException) {
                return fail(KeyboardUpdateErrorCode.TIMEOUT, "connection timed out")
            } catch (_: SSLException) {
                return fail(KeyboardUpdateErrorCode.TLS_ERROR, "TLS connection failed")
            } catch (_: IOException) {
                return fail(KeyboardUpdateErrorCode.NETWORK_ERROR, "network request failed")
            }
            try {
                when (val code = connection.responseCode) {
                    in 301..308 -> {
                        if (code !in REDIRECT_CODES) {
                            return fail(KeyboardUpdateErrorCode.NETWORK_ERROR, "status $code")
                        }
                        if (redirects >= maxRedirects) {
                            return fail(KeyboardUpdateErrorCode.REDIRECT_LIMIT, "$redirects redirects")
                        }
                        val location = connection.location
                            ?: return fail(KeyboardUpdateErrorCode.NETWORK_ERROR, "redirect without Location")
                        val next = current.resolve(location)
                        if (!schemeAllowed(next)) {
                            return fail(KeyboardUpdateErrorCode.REDIRECT_NOT_HTTPS, redactUrl(next))
                        }
                        if (next.rawUserInfo != null) {
                            return fail(KeyboardUpdateErrorCode.URL_USER_INFO, redactUrl(next))
                        }
                        redirects++
                        current = next
                    }
                    in 200..299 -> {
                        val body = readBounded(connection.body())
                            ?: return fail(KeyboardUpdateErrorCode.DOWNLOAD_TOO_LARGE, "$maxBytes bytes")
                        return Result.Ok(body, current)
                    }
                    else -> return fail(KeyboardUpdateErrorCode.NETWORK_ERROR, "status $code")
                }
            } catch (_: SocketTimeoutException) {
                return fail(KeyboardUpdateErrorCode.TIMEOUT, "connection timed out")
            } catch (_: SSLException) {
                return fail(KeyboardUpdateErrorCode.TLS_ERROR, "TLS connection failed")
            } catch (_: IOException) {
                return fail(KeyboardUpdateErrorCode.NETWORK_ERROR, "network request failed")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun readBounded(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        input.use { stream ->
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                out.write(chunk, 0, read)
            }
        }
        return out.toByteArray()
    }

    private fun fail(code: KeyboardUpdateErrorCode, detail: String): Result =
        Result.Fail(code, detail)

    private fun schemeAllowed(url: URI): Boolean {
        val scheme = url.scheme?.lowercase()
        return scheme == "https" || (allowHttp && scheme == "http")
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** Display form that never leaks credentials or query/fragment values. */
        fun redactUrl(url: URI): String {
            val host = url.host ?: ""
            val port = if (url.port > 0) ":${url.port}" else ""
            val path = url.rawPath?.ifBlank { "/" } ?: "/"
            val query = url.rawQuery?.takeIf { it.isNotEmpty() }?.let { raw ->
                "?" + raw.split('&').joinToString("&") { pair ->
                    val key = pair.substringBefore('=')
                    if (pair.contains('=')) "$key=***" else "$key=***"
                }
            } ?: ""
            val fragment = url.rawFragment?.takeIf { it.isNotEmpty() }?.let { "#***" } ?: ""
            return "https://$host$port$path$query$fragment"
        }

        /**
         * fail-closed target form for error details. Strips user info,
         * query and fragment BEFORE anything can be persisted or displayed;
         * never includes raw URL text.
         */
        fun sanitizeTarget(url: URI): String {
            val scheme = url.scheme?.lowercase() ?: "no-scheme"
            val host = url.host ?: "invalid-host"
            val port = if (url.port > 0) ":${url.port}" else ""
            val path = url.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            return "$scheme://$host$port$path"
        }
    }
}
