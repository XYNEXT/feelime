package com.feelime.ime.update

import org.json.JSONObject
import java.io.IOException
import java.net.URI

/** GitHub-release update source (design §8.1).
 *
 * The GitHub API is only a release index. We still apply the same package
 * trust boundary as the direct metainfo path: the selected asset must be an
 * HTTPS URL and the filename must identify a signed Feelime keyboard package.
 * The ZIP's Ed25519 envelope is checked later by [KeyboardPackageVerifier].
 */
class GithubReleaseSource(
    private val connectionFactory: UrlConnectionFactory,
    /** Injectable so JVM tests can use a deterministic mock HTTP transport. */
    private val apiBase: URI = API_BASE,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    sealed interface GithubResult {
        /** installUrl is an exact metainfo.json (preferred) or a signed ZIP. */
        data class Release(
            val installUrl: String,
            val version: String?,
            val isMetainfo: Boolean,
        ) : GithubResult

        /** code is stable and maps to the settings page dictionary. */
        data class Error(val code: String, val message: String) : GithubResult
    }

    /** Only HTTPS repository URLs on github.com are GitHub sources. */
    fun accepts(sourceUrl: String): Boolean = parseRepo(sourceUrl) != null

    fun fetch(sourceUrl: String): GithubResult {
        val repo = parseRepo(sourceUrl)
            ?: return error("INVALID_SOURCE", "GitHub 源地址需为 https://github.com/<owner>/<repo>")
        val api = runCatching {
            URI(
                "${apiBase.toString().trimEnd('/')}/repos/" +
                    "${repo.first}/${repo.second}/releases/latest",
            )
        }.getOrElse {
            return error("INVALID_SOURCE", "GitHub API 地址无效")
        }

        var backoffMs = 1_000L
        var lastCode = 0
        var lastWasRateLimited = false
        for (attempt in 1..MAX_ATTEMPTS) {
            val connection = try {
                connectionFactory.open(api)
            } catch (_: IOException) {
                lastCode = -1
                lastWasRateLimited = false
                null
            }
            if (connection != null) {
                try {
                    when (val code = connection.responseCode) {
                        200 -> {
                            val body = connection.body().use { it.readBytes() }.decodeToString()
                            return parse(body)
                        }
                        403, 429 -> {
                            // Anonymous GitHub clients are rate limited. Retry
                            // with bounded exponential backoff, then expose a
                            // stable code instead of pretending no release exists.
                            lastCode = code
                            lastWasRateLimited = true
                        }
                        404 -> return error("NO_RELEASE", "尚无可用发布")
                        else -> {
                            lastCode = code
                            lastWasRateLimited = false
                        }
                    }
                } catch (_: IOException) {
                    lastCode = -1
                    lastWasRateLimited = false
                } finally {
                    runCatching { connection.disconnect() }
                }
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    sleep(backoffMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                backoffMs = (backoffMs * 2).coerceAtMost(8_000L)
            }
        }
        return if (lastWasRateLimited) {
            error("RATE_LIMITED", "GitHub 请求受到限流（HTTP $lastCode）")
        } else {
            error("NETWORK_ERROR", "GitHub API 请求失败（HTTP $lastCode）")
        }
    }

    private fun parse(releaseJson: String): GithubResult {
        val release = runCatching { JSONObject(releaseJson) }.getOrNull()
            ?: return error("BAD_RESPONSE", "GitHub release 数据格式无效")
        val version = release.optString("tag_name").ifBlank { null }
        val assets = release.optJSONArray("assets")
            ?: return error("NO_INSTALLABLE_ASSET", "release 中没有可安装的键盘资产")
        val metainfo = mutableListOf<String>()
        val keyboardZips = mutableListOf<Pair<String, String>>()
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url").trim()
            when {
                // This is deliberately exact and case-sensitive. A release
                // containing two metainfo files is ambiguous even when one
                // happens to be listed first by the API.
                name == METAINFO_ASSET -> metainfo += url
                isCanonicalKeyboardZip(name) -> keyboardZips += name to url
            }
        }

        if (metainfo.size > 1) {
            return error("AMBIGUOUS_ASSET", "release 中有多个 metainfo.json 资产")
        }
        if (metainfo.size == 1) {
            val url = httpsUrl(metainfo.single())
                ?: return error("INVALID_ASSET_URL", "metainfo.json 的下载地址无效")
            return GithubResult.Release(url.toString(), version, isMetainfo = true)
        }

        if (keyboardZips.isEmpty()) {
            return error(
                "NO_INSTALLABLE_ASSET",
                "release 中没有符合 feelime-keyboard-<semver>.zip 命名的签名键盘包",
            )
        }
        // Multiple matching ZIPs do not have a safe ordering: GitHub's asset
        // array order is not a version-selection policy. Let the publisher
        // add one exact asset or metainfo.json instead of guessing.
        if (keyboardZips.size > 1) {
            return error("AMBIGUOUS_ASSET", "release 中有多个符合命名的键盘包")
        }
        val url = httpsUrl(keyboardZips.single().second)
            ?: return error("INVALID_ASSET_URL", "键盘包下载地址无效")
        return GithubResult.Release(url.toString(), version, isMetainfo = false)
    }

    private fun parseRepo(sourceUrl: String): Pair<String, String>? {
        val uri = runCatching { URI(sourceUrl.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host != "github.com" && uri.host != "www.github.com") return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val parts = uri.path.trim('/').split('/')
        if (parts.size != 2 || parts.any { !GITHUB_SEGMENT.matches(it) }) return null
        return parts[0] to parts[1]
    }

    private fun httpsUrl(raw: String): URI? {
        if (raw.isBlank()) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.isOpaque) return null
        return uri
    }

    private fun isCanonicalKeyboardZip(name: String): Boolean {
        if (!KEYBOARD_ZIP_NAME.matches(name)) return false
        val version = name.removePrefix("feelime-keyboard-").removeSuffix(".zip")
        val prerelease = version.substringBefore('+').substringAfter('-', "")
        // The debug packaging convention uses an `unsigned` prerelease label.
        // It is never an automatic GitHub candidate, regardless of whether a
        // publisher accidentally left a signature entry inside that ZIP.
        return prerelease.split('.').none { it.equals("unsigned", ignoreCase = true) }
    }

    private fun error(code: String, message: String): GithubResult.Error =
        GithubResult.Error(code, message)

    companion object {
        const val METAINFO_ASSET = "metainfo.json"
        const val MAX_ATTEMPTS = 3
        val API_BASE: URI = URI("https://api.github.com")

        private val GITHUB_SEGMENT = Regex("^[A-Za-z0-9_.-]+$")
        // GitHub's automatic source accepts only the production asset name.
        // In particular, a file called *-unsigned.zip is not selected even
        // when someone happened to embed a signature in it: this adapter only
        // sees metadata, while the package verifier owns signature truth.
        private val KEYBOARD_ZIP_NAME = Regex(
            "^feelime-keyboard-(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)" +
                "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?\\.zip$",
        )
    }
}
