package com.feelime.ime

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Call.cancel is persistent even before execute starts. HttpURLConnection's
 * disconnect is a no-op before its first connect, so it cannot close that
 * cancellation window when network consent changes. */
internal class CancellableModelTransport(
    private val client: OkHttpClient,
    private val allowHttp: Boolean,
    private val checkNetwork: () -> Unit,
) : ModelConnectionFactory, AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    override fun open(url: URI, rangeFrom: Long): ModelConnection {
        var current = url
        repeat(6) { hop ->
            checkActive()
            if (!current.scheme.equals("https", true) &&
                !(allowHttp && current.scheme.equals("http", true))) {
                throw IOException("Unsupported model download protocol")
            }
            val request = Request.Builder().url(current.toString()).apply {
                if (rangeFrom > 0) header("Range", "bytes=$rangeFrom-")
            }.build()
            val call = client.newCall(request)
            calls.add(call)
            // A callback may have cancelled existing calls before this call
            // was inserted. Persist cancellation before execute in that case.
            if (stopped.get()) call.cancel()
            val response = try {
                call.execute()
            } catch (error: IOException) {
                calls.remove(call)
                checkActive()
                throw error
            }
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                val location = response.header("Location")
                response.close()
                calls.remove(call)
                if (location.isNullOrBlank() || hop == 5) throw IOException("Invalid model redirect")
                current = current.resolve(location)
            } else {
                return object : ModelConnection {
                    override val responseCode = response.code
                    override fun body() = response.body?.byteStream()
                        ?: throw IOException("Empty model response")
                    override fun disconnect() {
                        response.close()
                        calls.remove(call)
                    }
                }
            }
        }
        throw IOException("Too many model redirects")
    }

    private fun checkActive() {
        if (stopped.get()) throw ModelDownloadNetworkChanged()
        checkNetwork()
    }

    override fun close() {
        stopped.set(true)
        calls.forEach { it.cancel() }
        calls.clear()
    }
}
