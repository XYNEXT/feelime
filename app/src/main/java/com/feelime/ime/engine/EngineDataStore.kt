package com.feelime.ime.engine

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Deploys the bundled engine data from APK assets into app-private storage
 * using the staging/rename harness proven by the native smoke spike: copying
 * happens in the background while the no-learning Direct engine keeps the
 * keyboard inputable, and a language only becomes selectable once its data
 * is deployed and hash-verified. Any deployed byte that no longer matches
 * the bundled manifest flips [mismatched], which surfaces to the coordinator
 * as ENGINE_DATA_MISMATCH (the N01 production trigger).
 */
object EngineDataStore {
    private const val ASSET_ROOT = "engine-data"
    private const val MANIFEST_ASSET = "$ASSET_ROOT/MANIFEST.json"

    @Volatile private var mismatch = false
    @Volatile private var deployFailed = false

    fun ensureAsync(context: Context, onComplete: () -> Unit = {}) {
        val appContext = context.applicationContext
        Thread {
            runCatching { ensure(appContext) }
            onComplete()
        }.apply { isDaemon = true }.start()
    }

    fun mismatched(): Boolean = mismatch

    /** Fast pointer check used for the HTML mode menu (no hashing). */
    fun isModeReady(context: Context, mode: InputMode): Boolean {
        val root = readyRoot(context) ?: return false
        return when (mode) {
            InputMode.DIRECT -> true
            InputMode.PINYIN -> File(root, "rime/luna_pinyin.schema.yaml").isFile
            InputMode.DOUBLE_PINYIN -> File(root, "rime/ziranma_double_pinyin.schema.yaml").isFile
            InputMode.FRENCH -> File(root, "hunspell/fr.aff").isFile
            InputMode.RUSSIAN -> File(root, "hunspell/ru_RU.aff").isFile
            InputMode.JAPANESE -> File(root, "mozc/mozc.data").isFile
        }
    }

    /**
     * Full hash verification for one engine group ("rime"/"hunspell"/"mozc").
     * Returns the ready data root, or null when the group is not deployed;
     * a hash mismatch sets the mismatch flag and also returns null.
     */
    fun verifyGroup(context: Context, group: String): File? {
        val root = readyRoot(context) ?: return null
        val manifest = manifest(context) ?: return null
        manifest.files.forEach { (path, entry) ->
            if (!path.startsWith("$group/")) return@forEach
            val file = File(root, path)
            if (!file.isFile || file.length() != entry.bytes || sha256(file) != entry.sha256) {
                mismatch = true
                return null
            }
        }
        return root
    }

    private fun readyRoot(context: Context): File? {
        // the CURRENT APK manifest hash is the only pointer. Never
        // select by directory-name ordering — an older version whose hash
        // sorts later would win forever and report a permanent mismatch.
        val parsed = manifest(context) ?: return null
        val version = sha256(parsed.raw)
        val target = File(File(context.filesDir, "engine-data/versions"), version)
        return if (File(target, ".ready").isFile) target else null
    }

    @Synchronized
    private fun ensure(context: Context) {
        val manifest = manifest(context) ?: run { deployFailed = true; return }
        val version = sha256(manifest.raw)
        val versions = File(context.filesDir, "engine-data/versions").apply { mkdirs() }
        val target = File(versions, version)
        if (File(target, ".ready").isFile) return
        val staging = File(versions, ".staging-$version")
        staging.deleteRecursively()
        staging.mkdirs()
        manifest.files.forEach { (path, entry) ->
            val outFile = File(staging, path)
            outFile.parentFile?.mkdirs()
            context.assets.open("$ASSET_ROOT/$path").use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                    // durable deploy — every byte is on disk before
                    // the version can be marked ready.
                    output.fd.sync()
                }
            }
            if (outFile.length() != entry.bytes || sha256(outFile) != entry.sha256) {
                mismatch = true
                staging.deleteRecursively()
                return
            }
        }
        File(staging, ".ready").outputStream().use { output ->
            output.write("ok\n".toByteArray())
            output.fd.sync()
        }
        syncDirectory(staging)
        if (target.isDirectory) target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            deployFailed = true
            return
        }
        syncDirectory(versions)
        // Only now is the current version complete and hash-verified, so any
        // other deployed version is safe to reclaim (cleanup rule).
        versions.listFiles { file -> file.isDirectory }
            ?.filter { it.name != version && !it.name.startsWith(".staging-") }
            ?.forEach { it.deleteRecursively() }
        versions.listFiles { file -> file.name.startsWith(".staging-") }
            ?.forEach { it.deleteRecursively() }
    }

    private fun syncDirectory(dir: File) {
        // Best-effort POSIX directory fsync so the rename itself survives
        // power loss; failures are non-fatal on filesystems that refuse it.
        runCatching {
            val fd = android.system.Os.open(dir.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try {
                android.system.Os.fsync(fd)
            } finally {
                android.system.Os.close(fd)
            }
        }
    }

    private data class ParsedManifest(val raw: ByteArray, val files: Map<String, Entry>) {
        data class Entry(val sha256: String, val bytes: Long)
    }

    private fun manifest(context: Context): ParsedManifest? {
        val raw = runCatching { context.assets.open(MANIFEST_ASSET).use { it.readBytes() } }
            .getOrNull() ?: return null
        return runCatching {
            val json = JSONObject(String(raw, Charsets.UTF_8))
            val filesObject = json.getJSONObject("files")
            val files = mutableMapOf<String, ParsedManifest.Entry>()
            for (key in filesObject.keys()) {
                val entry = filesObject.getJSONObject(key)
                files[key] = ParsedManifest.Entry(entry.getString("sha256"), entry.getLong("bytes"))
            }
            ParsedManifest(raw, files)
        }.getOrNull()
    }

    private fun sha256(file: File): String =
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
