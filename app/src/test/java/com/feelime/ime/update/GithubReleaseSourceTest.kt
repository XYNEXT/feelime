package com.feelime.ime.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI

/** design §8.1: the GitHub release source resolution (asset selection +
 * rate-limit backoff) without network. */
class GithubReleaseSourceTest {

    private class FakeConnection(
        private val codes: IntArray,
        private val body: String,
        private val openIndex: Int,
    ) : UrlConnection {
        override val responseCode: Int
            get() = codes[minOf(openIndex, codes.size - 1)]
        override val location: String? get() = null
        override fun body(): InputStream = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun disconnect() {}
    }

    private class FakeFactory(private val codes: IntArray, private val body: String) : UrlConnectionFactory {
        var opens = 0
        val urls = mutableListOf<URI>()
        override fun open(url: URI): UrlConnection {
            val index = opens
            opens += 1
            urls += url
            return FakeConnection(codes, body, index)
        }
    }

    private fun releaseJson(vararg assets: Pair<String, String>): String {
        val array = JSONArray()
        assets.forEach { (name, url) ->
            array.put(JSONObject().put("name", name).put("browser_download_url", url))
        }
        return JSONObject()
            .put("tag_name", "v0.17.0")
            .put("assets", array)
            .toString()
    }

    @Test
    fun acceptsOnlyGithubRepoUrls() {
        val source = GithubReleaseSource(FakeFactory(intArrayOf(200), "{}"))
        assertTrue(source.accepts("https://github.com/feelime/feelime"))
        assertTrue(source.accepts("  https://www.github.com/feelime/feelime  "))
        assertFalse(source.accepts("http://github.com/feelime/feelime"))
        assertFalse(source.accepts("https://github.com/feelime/feelime/releases"))
        assertFalse(source.accepts("https://github.com/feelime/feelime?tab=releases"))
        assertFalse(source.accepts("https://example.com/feelime/metainfo.json"))
        assertFalse(source.accepts("not a url ://"))
    }

    @Test
    fun metainfoAssetWinsOverZip() {
        val body = releaseJson(
            "feelime-keyboard-3.21.0.zip" to "https://github.com/f/ke/releases/download/v0.17.0/feelime-keyboard-3.21.0.zip",
            "metainfo.json" to "https://github.com/f/ke/releases/download/v0.17.0/metainfo.json",
        )
        val factory = FakeFactory(intArrayOf(200), body)
        val result = GithubReleaseSource(factory).fetch("https://github.com/feelime/feelime")
        val release = result as GithubReleaseSource.GithubResult.Release
        assertEquals("https://github.com/f/ke/releases/download/v0.17.0/metainfo.json", release.installUrl)
        assertTrue(release.isMetainfo)
        assertEquals("v0.17.0", release.version)
        assertEquals(1, factory.opens)
    }

    @Test
    fun zipOnlyReleaseResolvesToTheNamedSignedZip() {
        val body = releaseJson(
            "feelime-keyboard-3.22.0.zip" to "https://github.com/f/ke/releases/download/v1/feelime-keyboard-3.22.0.zip",
            "model.zip" to "https://github.com/f/ke/releases/download/v1/model.zip",
        )
        val result = GithubReleaseSource(FakeFactory(intArrayOf(200), body))
            .fetch("https://github.com/feelime/feelime")
        val release = result as GithubReleaseSource.GithubResult.Release
        assertEquals("https://github.com/f/ke/releases/download/v1/feelime-keyboard-3.22.0.zip", release.installUrl)
        assertFalse(release.isMetainfo)
    }

    @Test
    fun arbitraryZipAndUnsignedNameAreRejected() {
        val body = releaseJson(
            "kb.zip" to "https://github.com/f/ke/releases/download/v1/kb.zip",
            "feelime-keyboard-3.22.0-unsigned.zip" to
                "https://github.com/f/ke/releases/download/v1/feelime-keyboard-3.22.0-unsigned.zip",
        )
        val result = GithubReleaseSource(FakeFactory(intArrayOf(200), body), sleep = {})
            .fetch("https://github.com/feelime/feelime")
        val error = result as GithubReleaseSource.GithubResult.Error
        assertEquals("NO_INSTALLABLE_ASSET", error.code)
    }

    @Test
    fun multipleMatchingAssetsAreRejectedInsteadOfUsingArrayOrder() {
        val body = releaseJson(
            "feelime-keyboard-3.21.0.zip" to "https://github.com/f/ke/releases/download/v1/a.zip",
            "feelime-keyboard-3.22.0.zip" to "https://github.com/f/ke/releases/download/v1/b.zip",
        )
        val result = GithubReleaseSource(FakeFactory(intArrayOf(200), body), sleep = {})
            .fetch("https://github.com/feelime/feelime")
        val error = result as GithubReleaseSource.GithubResult.Error
        assertEquals("AMBIGUOUS_ASSET", error.code)
    }

    @Test
    fun duplicateMetainfoAndInsecureAssetUrlAreRejected() {
        val duplicate = releaseJson(
            "metainfo.json" to "https://github.com/f/ke/releases/download/v1/a.json",
            "metainfo.json" to "https://github.com/f/ke/releases/download/v1/b.json",
        )
        val duplicateResult = GithubReleaseSource(FakeFactory(intArrayOf(200), duplicate), sleep = {})
            .fetch("https://github.com/feelime/feelime")
        assertEquals("AMBIGUOUS_ASSET", (duplicateResult as GithubReleaseSource.GithubResult.Error).code)

        val insecure = releaseJson(
            "feelime-keyboard-3.22.0.zip" to "http://github.com/f/ke/releases/download/v1/kb.zip",
        )
        val insecureResult = GithubReleaseSource(FakeFactory(intArrayOf(200), insecure), sleep = {})
            .fetch("https://github.com/feelime/feelime")
        assertEquals("INVALID_ASSET_URL", (insecureResult as GithubReleaseSource.GithubResult.Error).code)
    }

    @Test
    fun rateLimitBacksOffThenSucceeds() {
        val body = releaseJson("metainfo.json" to "https://github.com/f/ke/releases/download/v1/metainfo.json")
        val factory = FakeFactory(intArrayOf(403, 200), body)
        val result = GithubReleaseSource(factory, sleep = {}).fetch("https://github.com/feelime/feelime")
        assertTrue(result is GithubReleaseSource.GithubResult.Release)
        assertEquals(2, factory.opens)
    }

    @Test
    fun exhaustedBackoffSurfacesAnError() {
        val factory = FakeFactory(intArrayOf(429), "{}")
        val result = GithubReleaseSource(factory, sleep = {}).fetch("https://github.com/feelime/feelime")
        val error = result as GithubReleaseSource.GithubResult.Error
        assertEquals("RATE_LIMITED", error.code)
        assertEquals(GithubReleaseSource.MAX_ATTEMPTS, factory.opens)
    }

    @Test
    fun missingReleasesNameTheProblem() {
        val notFound = GithubReleaseSource(FakeFactory(intArrayOf(404), "")).fetch("https://github.com/f/ke")
        assertEquals("NO_RELEASE", (notFound as GithubReleaseSource.GithubResult.Error).code)
        assertEquals("尚无可用发布", notFound.message)
        val noAssets = GithubReleaseSource(FakeFactory(intArrayOf(200), "{}")).fetch("https://github.com/f/ke")
        assertEquals("NO_INSTALLABLE_ASSET", (noAssets as GithubReleaseSource.GithubResult.Error).code)
    }

    @Test
    fun mockHttpFetchesTheExpectedLatestReleaseEndpoint() {
        val body = releaseJson(
            "metainfo.json" to "https://github.com/f/ke/releases/download/v1/metainfo.json",
        )
        // FakeFactory is a dependency-free mock HTTP transport. It records
        // the request URI while returning the same JSON body an API server
        // would send, so the test stays runnable with Android's JVM stubs.
        val mock = FakeFactory(intArrayOf(200), body)
        val result = GithubReleaseSource(mock, sleep = {})
            .fetch("https://github.com/feelime/feelime")
        assertTrue(result is GithubReleaseSource.GithubResult.Release)
        assertEquals(
            "https://api.github.com/repos/feelime/feelime/releases/latest",
            mock.urls.single().toString(),
        )
    }
}
