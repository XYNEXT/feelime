package com.feelime.ime.update

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardUpdateDownloaderTest {
    private class FakeConnection(
        override val responseCode: Int,
        override val location: String? = null,
        private val body: ByteArray = ByteArray(0),
    ) : UrlConnection {
        override fun body(): InputStream = ByteArrayInputStream(body)
        override fun disconnect() {}
    }

    private class Script(val factory: FakeFactory, block: Script.() -> Unit) {
        var methods = mutableListOf<String>()
        init { block() }
    }

    private class FakeFactory : UrlConnectionFactory {
        val requests = mutableListOf<URI>()
        val responses = mutableListOf<Any>() // FakeConnection or IOException
        override fun open(url: URI): UrlConnection {
            requests.add(url)
            val next = responses.removeAt(0)
            if (next is RuntimeException) throw next
            if (next is IOException) throw next
            return next as FakeConnection
        }
    }

    private fun downloader(factory: FakeFactory, maxBytes: Long = 1024) =
        KeyboardUpdateDownloader(factory, maxBytes = maxBytes)

    @Test
    fun httpsOnlyAndNoUserInfo() {
        val factory = FakeFactory()
        val result = downloader(factory).download(URI("http://example.com/kb.zip"))
        assertTrue(result is KeyboardUpdateDownloader.Result.Fail)
        assertEquals(KeyboardUpdateErrorCode.URL_NOT_HTTPS, (result as KeyboardUpdateDownloader.Result.Fail).code)

        val result2 = downloader(factory).download(URI("https://user:secret@example.com/kb.zip"))
        assertTrue(result2 is KeyboardUpdateDownloader.Result.Fail)
        assertEquals(KeyboardUpdateErrorCode.URL_USER_INFO, (result2 as KeyboardUpdateDownloader.Result.Fail).code)
        // No connection was attempted for either.
        assertTrue(factory.requests.isEmpty())
    }

    @Test
    fun plainHttpOnlyWhenOptedIn() {
        // Default: http still refused.
        val strict = FakeFactory()
        assertTrue(downloader(strict).download(URI("http://intranet/kb.zip"))
            is KeyboardUpdateDownloader.Result.Fail)
        assertTrue(strict.requests.isEmpty())

        // Debug loop: allowHttp admits http (and its redirects).
        val factory = FakeFactory()
        factory.responses.add(FakeConnection(302, location = "http://intranet/mirror/kb.zip"))
        factory.responses.add(FakeConnection(200, body = ByteArray(10)))
        val result = KeyboardUpdateDownloader(factory, allowHttp = true)
            .download(URI("http://intranet/kb.zip"))
        assertTrue("http fetch succeeds when opted in",
            result is KeyboardUpdateDownloader.Result.Ok)

        // Opted-in http still rejects https-less redirects to other schemes
        // and credentials remain refused.
        val factory2 = FakeFactory()
        assertTrue(
            KeyboardUpdateDownloader(factory2, allowHttp = true)
                .download(URI("ftp://intranet/kb.zip")) is KeyboardUpdateDownloader.Result.Fail,
        )
        val factory3 = FakeFactory()
        assertTrue(
            KeyboardUpdateDownloader(factory3, allowHttp = true)
                .download(URI("http://user:pw@intranet/kb.zip")) is KeyboardUpdateDownloader.Result.Fail,
        )
        assertTrue(factory3.requests.isEmpty())
    }

    @Test
    fun g2B12NotHttpsDetailNeverLeaksUserinfoQueryOrFragment() {
        val factory = FakeFactory()
        val result = downloader(factory).download(
            URI("http://user:secret@example.com/kb.zip?token=abc123#sha256=deadbeef")
        )
        assertTrue(result is KeyboardUpdateDownloader.Result.Fail)
        val detail = (result as KeyboardUpdateDownloader.Result.Fail).detail
        assertEquals("http://example.com/kb.zip", detail)
        assertFalse(detail.contains("user"))
        assertFalse(detail.contains("secret"))
        assertFalse(detail.contains("token"))
        assertFalse(detail.contains("abc123"))
        assertFalse(detail.contains("deadbeef"))
    }

    @Test
    fun g2B12RedirectToUserinfoUrlFailsWithSanitizedDetail() {
        val factory = FakeFactory()
        factory.responses.apply {
            add(FakeConnection(302, "https://user:pass@next.example/kb.zip"))
        }
        val result = downloader(factory).download(URI("https://start.example/kb.zip"))
        assertTrue(result is KeyboardUpdateDownloader.Result.Fail)
        val fail = result as KeyboardUpdateDownloader.Result.Fail
        assertEquals(KeyboardUpdateErrorCode.URL_USER_INFO, fail.code)
        assertFalse(fail.detail.contains("pass"))
    }

    @Test
    fun threeHopsAllowedAllRedirectCodesGetStaysGet() {
        val factory = FakeFactory()
        factory.responses.apply {
            add(FakeConnection(301, "https://a.example/one"))
            add(FakeConnection(302, "https://b.example/two"))
            add(FakeConnection(303, "https://c.example/three"))
            add(FakeConnection(307, "https://d.example/four"))
            add(FakeConnection(308, "https://e.example/final"))
            add(FakeConnection(200, body = byteArrayOf(1, 2, 3)))
        }
        val result = KeyboardUpdateDownloader(factory, maxRedirects = 5)
            .download(URI("https://start.example/kb.zip"))
        assertTrue("got $result", result is KeyboardUpdateDownloader.Result.Ok)
        val ok = result as KeyboardUpdateDownloader.Result.Ok
        assertEquals(URI("https://e.example/final"), ok.effectiveUrl)
        // Every redirect kind passed through; six requests total (five hops + body).
        assertEquals(6, factory.requests.size)
    }

    @Test
    fun fourthHopRejected() {
        val factory = FakeFactory()
        factory.responses.apply {
            add(FakeConnection(301, "https://a.example/1"))
            add(FakeConnection(302, "https://a.example/2"))
            add(FakeConnection(303, "https://a.example/3"))
            add(FakeConnection(307, "https://a.example/4"))
        }
        val result = downloader(factory).download(URI("https://start.example/kb.zip"))
        assertEquals(
            KeyboardUpdateErrorCode.REDIRECT_LIMIT,
            (result as KeyboardUpdateDownloader.Result.Fail).code,
        )
    }

    @Test
    fun downgradeAndRedirectUserInfoRejected() {
        val factory = FakeFactory()
        factory.responses.add(FakeConnection(302, "http://plain.example/kb.zip"))
        assertEquals(
            KeyboardUpdateErrorCode.REDIRECT_NOT_HTTPS,
            (downloader(factory).download(URI("https://start.example/kb.zip")) as KeyboardUpdateDownloader.Result.Fail).code,
        )
        val factory2 = FakeFactory()
        factory2.responses.add(FakeConnection(302, "https://user:pw@next.example/kb.zip"))
        assertEquals(
            KeyboardUpdateErrorCode.URL_USER_INFO,
            (downloader(factory2).download(URI("https://start.example/kb.zip")) as KeyboardUpdateDownloader.Result.Fail).code,
        )
    }

    @Test
    fun redirectWithoutLocationIsNetworkError() {
        val factory = FakeFactory()
        factory.responses.add(FakeConnection(302, location = null))
        assertEquals(
            KeyboardUpdateErrorCode.NETWORK_ERROR,
            (downloader(factory).download(URI("https://start.example/kb.zip")) as KeyboardUpdateDownloader.Result.Fail).code,
        )
    }

    @Test
    fun tlsTimeoutIoAndStatusErrorsClassified() {
        val tls = FakeFactory()
        tls.responses.add(SSLException("bad cert"))
        assertEquals(
            KeyboardUpdateErrorCode.TLS_ERROR,
            (downloader(tls).download(URI("https://start.example/kb.zip")) as KeyboardUpdateDownloader.Result.Fail).code,
        )
        val timeout = FakeFactory()
        timeout.responses.add(SocketTimeoutException("read timed out"))
        assertEquals(
            KeyboardUpdateErrorCode.TIMEOUT,
            (downloader(timeout).download(URI("https://start.example/kb.zip")) as KeyboardUpdateDownloader.Result.Fail).code,
        )
        val io = FakeFactory()
        io.responses.add(IOException("connection reset"))
        assertEquals(
            KeyboardUpdateErrorCode.NETWORK_ERROR,
            (downloader(io).download(URI("https://start.example/kb.zip")) as KeyboardUpdateDownloader.Result.Fail).code,
        )
        val status = FakeFactory()
        status.responses.add(FakeConnection(404))
        assertEquals(
            KeyboardUpdateErrorCode.NETWORK_ERROR,
            (downloader(status).download(URI("https://start.example/kb.zip")) as KeyboardUpdateDownloader.Result.Fail).code,
        )
        // 300/304 are not accepted redirect codes.
        val notModified = FakeFactory()
        notModified.responses.add(FakeConnection(304, "https://next.example/kb.zip"))
        assertEquals(
            KeyboardUpdateErrorCode.NETWORK_ERROR,
            (downloader(notModified).download(URI("https://start.example/kb.zip")) as KeyboardUpdateDownloader.Result.Fail).code,
        )
    }

    @Test
    fun transportExceptionMessagesNeverReachPersistentDetail() {
        val secret = "https://user:secret@example.test/update.zip?token=private"
        val failures = listOf(
            SocketTimeoutException(secret) to KeyboardUpdateErrorCode.TIMEOUT,
            SSLException(secret) to KeyboardUpdateErrorCode.TLS_ERROR,
            IOException(secret) to KeyboardUpdateErrorCode.NETWORK_ERROR,
        )
        failures.forEach { (failure, expectedCode) ->
            val result = KeyboardUpdateDownloader(
                object : UrlConnectionFactory {
                    override fun open(url: URI): UrlConnection = throw failure
                },
            ).download(URI("https://example.test/update.zip")) as KeyboardUpdateDownloader.Result.Fail
            assertEquals(expectedCode, result.code)
            assertFalse(result.detail.contains("secret"))
            assertFalse(result.detail.contains("private"))
            assertFalse(result.detail.contains("example.test"))
        }
    }

    @Test
    fun downloadSizeCapped() {
        val factory = FakeFactory()
        factory.responses.add(FakeConnection(200, body = ByteArray(2048)))
        assertEquals(
            KeyboardUpdateErrorCode.DOWNLOAD_TOO_LARGE,
            (downloader(factory, maxBytes = 1024).download(URI("https://start.example/kb.zip")) as KeyboardUpdateDownloader.Result.Fail).code,
        )
    }

    @Test
    fun redactionStripsUserInfoAndMasksValues() {
        assertEquals(
            "https://example.com/kb.zip",
            KeyboardUpdateDownloader.redactUrl(URI("https://example.com/kb.zip")),
        )
        val redacted = KeyboardUpdateDownloader.redactUrl(
            URI("https://user:secret@example.com:8443/kb.zip?token=abc123&refresh=1#sha256=deadbeef"),
        )
        assertEquals("https://example.com:8443/kb.zip?token=***&refresh=***#***", redacted)
        assertTrue("no credentials leak", !redacted.contains("secret"))
        assertTrue("no token leak", !redacted.contains("abc123"))
    }

    // ---------------- Metainfo source parsing ----------------

    @Test
    fun metainfoParsesUrlAndVersion() {
        val text = "{\n  \"keyboard_version\": \"3.16.0\",\n  \"url\": \"https://example.com/feelime-keyboard-3.16.0-unsigned.zip\"\n}\n"
        val info = UpdateMetainfo.parse(text)
        assertEquals("https://example.com/feelime-keyboard-3.16.0-unsigned.zip", info.url)
        assertEquals("3.16.0", info.version)
    }

    @Test
    fun metainfoVersionIsOptionalUrlIsRequired() {
        val info = UpdateMetainfo.parse("""{"url":"https://example.com/kb.zip"}""")
        assertEquals("https://example.com/kb.zip", info.url)
        assertNull(info.version)
        assertNull(UpdateMetainfo.parse("""{"keyboard_version":"3.16.0"}""").url)
        assertNull(UpdateMetainfo.parse("").url)
        // A value of empty string is as good as absent.
        assertNull(UpdateMetainfo.parse("""{"url":""}""").url)
    }

    @Test
    fun metainfoToleratesWhitespaceAndAdjacentKeys() {
        val info = UpdateMetainfo.parse(
            """{"other":"url_not_this","url" :  "https://example.com/kb.zip","keyboard_version":"1.2.3"}""",
        )
        // "other" contains the substring url but is a different KEY - only
        // the exact "url" key's value is taken.
        assertEquals("https://example.com/kb.zip", info.url)
        assertEquals("1.2.3", info.version)
    }
}
