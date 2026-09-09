package com.feelime.ime.update

import java.io.File
import java.io.FileOutputStream

/** Pluggable key-value persistence (SharedPreferences in the app, map in tests). */
interface KeyboardStateStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** Crash-injection hooks so recovery can be tested at every checkpoint. */
interface UpdateFaults {
    fun at(point: String)
    object None : UpdateFaults {
        override fun at(point: String) {}
    }
}

/** The APK-bundled keyboard, abstracted for JVM tests. */
interface BuiltInKeyboardSource {
    fun version(): String
    fun files(): Map<String, ByteArray>
}

enum class KeyboardSource { BUILT_IN, ACTIVE_VERSION }

class ActiveKeyboard(
    val dir: File,
    val source: KeyboardSource,
    val version: String,
    val contentHash: String?,
    val signed: Boolean,
    val manifest: KeyboardManifest?,
    /** 签名不符但被用户显式确认导入（docs/design/userdata.md §3）。 */
    val signatureConfirmed: Boolean = false,
) {
    /** What the IME compares to decide whether the WebView must reload. */
    val revision: String get() = contentHash ?: "built-in:$version"
}

class KeyboardStore(
    private val root: File,
    private val builtIn: BuiltInKeyboardSource,
    private val verifier: KeyboardPackageVerifier,
    val state: KeyboardStateStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val faults: UpdateFaults = UpdateFaults.None,
    private val allowUnsignedVersions: Boolean = false,
) {
    sealed class InstallResult {
        class Ok(val manifest: KeyboardManifest, val alreadyCurrent: Boolean) : InstallResult()
        class Fail(val code: KeyboardUpdateErrorCode, val detail: String) : InstallResult()
    }

    private val versionsDir get() = File(root, "versions")
    private val builtInDir get() = File(root, "built-in")
    private val pointerFile get() = File(root, "active.json")

    /** §8.3: staging -> fsync -> rename -> pointer swap is the only switch.
     * [confirmBadSignature]（docs/design/userdata.md §3）：用户显式确认后的
     * 签名不符导入；落盘带 `.signature-confirmed` 标记，resolve() 靠它放行。 */
    fun install(zip: ByteArray, fragmentPin: String? = null, confirmBadSignature: Boolean = false): InstallResult {
        state.put(STATE_UPDATE_STATE, "VERIFYING")
        state.put(STATE_LAST_ATTEMPT_AT, clock().toString())
        val verified = verifier.verify(zip, acceptBadSignature = confirmBadSignature)
        val pkg = when (verified) {
            is KeyboardPackageVerifier.Result.Rejected -> {
                recordFailure(verified.code, verified.detail)
                return InstallResult.Fail(verified.code, verified.detail)
            }
            is KeyboardPackageVerifier.Result.Ok -> verified.pkg
        }
        state.put(STATE_UPDATE_STATE, "CHECKING_COMPATIBILITY")
        if (pkg.manifest.minNativeApi > BridgeContract.NATIVE_API_VERSION) {
            return failed(KeyboardUpdateErrorCode.COMPAT_MIN_NATIVE_API, "${pkg.manifest.minNativeApi}")
        }
        if (!BridgeContract.isCompatible(pkg.manifest.minNativeApi, pkg.manifest.requiredCapabilities)) {
            return failed(KeyboardUpdateErrorCode.COMPAT_CAPABILITIES, pkg.manifest.requiredCapabilities.joinToString())
        }
        if (fragmentPin != null && pkg.zipSha256 != fragmentPin.lowercase()) {
            return failed(KeyboardUpdateErrorCode.FRAGMENT_PIN_MISMATCH, fragmentPin)
        }
        state.put(STATE_UPDATE_STATE, "READY")

        val hash = pkg.manifest.contentHash
        val target = File(versionsDir, hash)
        if (target.isDirectory) {
            // Immutable version already present: only the pointer may need to
            // move. A confirmed install must leave the marker behind even
            // here - the pre-existing directory predates the confirmation.
            if (confirmBadSignature && !pkg.signed &&
                !File(target, SIGNATURE_CONFIRMED_MARKER).exists()
            ) {
                writeSynced(File(target, SIGNATURE_CONFIRMED_MARKER), ByteArray(0))
            }
            faults.at("before-pointer")
            val swapped = swapPointer(hash)
            faults.at("after-pointer")
            val signed = File(target, KeyboardPackageVerifier.SIGNATURE_ENTRY).isFile &&
                !File(target, UNSIGNED_MARKER).exists()
            recordSuccess(pkg.manifest, signed = signed, alreadyCurrent = !swapped,
                confirmed = confirmBadSignature && !pkg.signed)
            return InstallResult.Ok(pkg.manifest, alreadyCurrent = !swapped)
        }

        val staging = File(versionsDir, "$hash.staging")
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            return failed(KeyboardUpdateErrorCode.IO_ERROR, "mkdir $staging")
        }
        try {
            for (name in KeyboardPackageVerifier.PAYLOAD_ENTRIES) {
                writeSynced(File(staging, name), pkg.files.getValue(name))
                faults.at("staging-write")
            }
            writeSynced(File(staging, KeyboardPackageVerifier.MANIFEST_ENTRY), pkg.manifest.rawBytes)
            if (confirmBadSignature && !pkg.signed) {
                // 确认导入：签名字节照存（留痕），加确认标记让 resolve() 放行。
                val signature = extractSignature(zip)
                if (signature == null) {
                    staging.deleteRecursively()
                    return failed(
                            KeyboardUpdateErrorCode.SIGNATURE_MISSING,
                            KeyboardPackageVerifier.SIGNATURE_ENTRY,
                        )
                }
                writeSynced(File(staging, KeyboardPackageVerifier.SIGNATURE_ENTRY), signature)
                writeSynced(File(staging, SIGNATURE_CONFIRMED_MARKER), ByteArray(0))
            } else if (pkg.signed) {
                val signature = extractSignature(zip) ?: ByteArray(0)
                writeSynced(File(staging, KeyboardPackageVerifier.SIGNATURE_ENTRY), signature)
            } else {
                writeSynced(File(staging, UNSIGNED_MARKER), ByteArray(0))
            }
        } catch (exception: java.io.IOException) {
            staging.deleteRecursively()
            return failed(KeyboardUpdateErrorCode.IO_ERROR, exception.message ?: "staging")
        }

        faults.at("before-rename")
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            return failed(KeyboardUpdateErrorCode.IO_ERROR, "rename $target")
        }
        fsyncDirectory(versionsDir)
        faults.at("after-rename")

        state.put(STATE_UPDATE_STATE, "ACTIVATING")
        faults.at("before-pointer")
        val swapped = swapPointer(hash)
        faults.at("after-pointer")
        if (!swapped) {
            return failed(KeyboardUpdateErrorCode.IO_ERROR, "pointer")
        }
        recordSuccess(pkg.manifest, signed = pkg.signed, alreadyCurrent = false,
            confirmed = confirmBadSignature && !pkg.signed)
        return InstallResult.Ok(pkg.manifest, alreadyCurrent = false)
    }

    fun restoreBuiltIn(): Boolean {
        faults.at("before-pointer")
        val swapped = swapPointer(null)
        faults.at("after-pointer")
        if (swapped) {
            // The user asked for the APK keyboard — re-seed the
            // built-in copy NOW. resolve() never re-seeds while the PREVIOUS
            // hot-update entry still loads (it rolls back to that instead),
            // so a device that ever hot-updated could never reach the
            // freshly installed APK keyboard, not even via this button.
            seedBuiltIn()
            state.put(STATE_UPDATE_STATE, "BUILT_IN")
            state.put(STATE_SOURCE_TYPE, KeyboardSource.BUILT_IN.name)
        }
        return swapped
    }

    /**
     * Startup path: re-verify the active version, fall back to previous, then
     * to the APK built-in keyboard. Never returns null — there is always a
     * keyboard to serve (design §8.3).
     */
    fun resolve(): ActiveKeyboard {
        val pointer = readPointer()
        if (pointer != null && pointer.active.isNotEmpty()) {
            val active = loadVersion(pointer.active)
            if (active != null && !olderThanBuiltIn(active.version)) return active
            // Roll back to the previous HOT-UPDATE version only when an
            // active pointer existed: an EMPTY active is restoreBuiltIn()'s
            // mark - falling back to `previous` there would resurrect the
            // hot-update the user just left (the rollback branch
            // used to shadow seedBuiltIn forever, so "restore built-in"
            // never actually reached the freshly installed APK keyboard).
            val previous = loadVersion(pointer.previous)
            if (previous != null && !olderThanBuiltIn(previous.version)) {
                state.put(STATE_UPDATE_STATE, "ROLLED_BACK")
                return previous
            }
            // Overwrite-install residue: the active (or
            // previous) hot-update is OLDER than the freshly installed APK
            // built-in keyboard. It must not shadow it - the stale keyboard's
            // old capability handshake can never complete against the new
            // native (pre-v2 keyboards are rejected on purpose),
            // leaving the IME dead. Switch the pointer back, re-seed and
            // serve the APK keyboard.
            if (active != null || previous != null) {
                swapPointer(null)
                state.put(STATE_UPDATE_STATE, "BUILT_IN")
                state.put(STATE_SOURCE_TYPE, KeyboardSource.BUILT_IN.name)
            } else {
                state.put(STATE_UPDATE_STATE, "ROLLED_BACK")
            }
        }
        seedBuiltIn()
        return ActiveKeyboard(
            dir = builtInDir,
            source = KeyboardSource.BUILT_IN,
            version = builtIn.version(),
            contentHash = null,
            signed = true,
            manifest = null,
        )
    }

    /** §8.3: only after a successful keyboardReady handshake; keeps previous. */
    fun onHandshakeComplete() {
        val pointer = readPointer() ?: return
        val keep = mutableSetOf(pointer.active, pointer.previous).filter { it.isNotEmpty() }
        versionsDir.listFiles()?.forEach { entry ->
            val base = entry.name.removeSuffix(".staging")
            if (entry.name.endsWith(".staging") || base !in keep) {
                entry.deleteRecursively()
            }
        }
    }

    private fun loadVersion(hash: String): ActiveKeyboard? {
        if (hash.isEmpty()) return null
        val dir = File(versionsDir, hash)
        if (!dir.isDirectory) return null
        val manifestBytes = File(dir, KeyboardPackageVerifier.MANIFEST_ENTRY).takeIf { it.isFile }
            ?.readBytes() ?: return null
        val manifest = when (val parsed = KeyboardManifestRecovery.parse(manifestBytes)) {
            is KeyboardManifestRecovery.Outcome.Ok -> parsed.manifest
            KeyboardManifestRecovery.Outcome.Err -> return null
        }
        // The directory name must bind to the manifest content hash, and the
        // stored signature must still verify against a release key (§8.3).
        // Unsigned (debug-only) versions carry a marker instead of a signature;
        // signature-confirmed versions (userdata.md §3) carry a REAL signature
        // that fails verification PLUS the confirmation marker - only that
        // combination is accepted without a valid signature.
        if (manifest.contentHash != hash) return null
        val signatureFile = File(dir, KeyboardPackageVerifier.SIGNATURE_ENTRY)
        val unsignedMarker = File(dir, UNSIGNED_MARKER).exists()
        val confirmedMarker = File(dir, SIGNATURE_CONFIRMED_MARKER).exists()
        val signedVersion = signatureFile.isFile &&
            verifier.verifyStoredSignature(manifestBytes, manifest.keyId, signatureFile.readBytes())
        // 可接受的三个路径：签名有效；或用户确认过的签名不符（§3）；
        // 或调试构建的免签版本（allowUnsignedVersions）。
        if (!signedVersion && !confirmedMarker && !allowUnsignedVersions) {
            return null
        }
        for (name in KeyboardPackageVerifier.PAYLOAD_ENTRIES) {
            val file = File(dir, name)
            if (!file.isFile) return null
            val expected = manifest.payload[name] ?: return null
            if (file.length() != expected.second) return null
            if (KeyboardPackageVerifier.sha256Hex(file.readBytes()) != expected.first) return null
        }
        if (!BridgeContract.isCompatible(manifest.minNativeApi, manifest.requiredCapabilities)) {
            return null
        }
        val versionFile = File(dir, "VERSION").readText().trim()
        if (versionFile != manifest.keyboardVersion) return null
        return ActiveKeyboard(
            dir = dir,
            source = KeyboardSource.ACTIVE_VERSION,
            version = manifest.keyboardVersion,
            contentHash = manifest.contentHash,
            signed = signedVersion,
            manifest = manifest,
            signatureConfirmed = !signedVersion && confirmedMarker,
        )
    }

    private fun seedBuiltIn() {
        val versionFile = File(builtInDir, "VERSION")
        if (builtInDir.isDirectory && versionFile.isFile &&
            versionFile.readText().trim() == builtIn.version() &&
            builtInCopyMatchesApk()
        ) {
            return
        }
        val staging = File(root, "built-in.staging")
        staging.deleteRecursively()
        if (!staging.mkdirs()) return
        try {
            for ((name, bytes) in builtIn.files()) {
                writeSynced(File(staging, name), bytes)
            }
        } catch (exception: java.io.IOException) {
            staging.deleteRecursively()
            return
        }
        val old = File(root, "built-in.old")
        old.deleteRecursively()
        if (builtInDir.isDirectory) builtInDir.renameTo(old)
        staging.renameTo(builtInDir)
        old.deleteRecursively()
    }

    /** VERSION alone cannot detect a same-version content change: an APK
     * upgrade whose VERSION stayed put would silently keep serving the stale
     * seeded JS forever. Compare the payload bytes too (four small files). */
    private fun builtInCopyMatchesApk(): Boolean =
        builtIn.files().all { (name, bytes) ->
            val file = File(builtInDir, name)
            file.isFile && file.readBytes().contentEquals(bytes)
        }

    /** True when a hot-update keyboardVersion predates the APK built-in -
     * i.e. the pointer is overwrite-install residue, not a user choice to
     * run ahead of the APK. Equal versions stay (a same-version hot-update
     * is a deliberate content fix). Pre-release/build suffixes (3.20.0-rc1,
     * 3.21.0+build.5 - SEMVER allows both) are stripped before the numeric
     * compare so a stale -rc cannot silently survive an APK upgrade. */
    private fun olderThanBuiltIn(version: String): Boolean {
        fun core(s: String) = s.substringBefore('-').substringBefore('+')
        val a = core(version).split('.').map { it.toIntOrNull() ?: return false }
        val b = core(builtIn.version()).split('.').map { it.toIntOrNull() ?: return false }
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (a.getOrNull(i) ?: 0) - (b.getOrNull(i) ?: 0)
            if (d != 0) return d < 0
        }
        return false
    }

    /** Returns true when the pointer content changed. */
    private fun swapPointer(newActive: String?): Boolean {
        val current = readPointer()
        val previousActive = current?.active ?: ""
        val previous = if (newActive != null && previousActive != newActive) previousActive else current?.previous ?: ""
        val nextActive = newActive ?: ""
        if (current != null && current.active == nextActive && current.previous == previous) {
            return false
        }
        val payload = CanonicalJson.serialize(
            CanonicalJson.Value.Obj(
                listOf(
                    "activatedAt" to CanonicalJson.Value.Num(clock()),
                    "active" to CanonicalJson.Value.Str(nextActive),
                    "previous" to CanonicalJson.Value.Str(previous),
                ),
            ),
        )
        faults.at("pointer-write-mid")
        val tmp = File(root, "active.json.tmp")
        writeSynced(tmp, payload)
        if (!tmp.renameTo(pointerFile)) return false
        fsyncDirectory(root)
        faults.at("pointer-write-after")
        return true
    }

    private fun readPointer(): Pointer? {
        if (!pointerFile.isFile) return null
        return try {
            val root = CanonicalJson.parseAndCheck(pointerFile.readBytes())
            if (root !is CanonicalJson.Value.Obj) return null
            val active = (root["active"] as? CanonicalJson.Value.Str)?.value ?: return null
            val previous = (root["previous"] as? CanonicalJson.Value.Str)?.value ?: ""
            Pointer(active, previous)
        } catch (exception: CanonicalJson.FormatException) {
            null
        }
    }

    private class Pointer(val active: String, val previous: String)

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    /** Durability of renames needs a directory fsync; best-effort (JVM tests skip). */
    private fun fsyncDirectory(dir: File) {
        runCatching {
            val fd = android.system.Os.open(dir.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try {
                android.system.Os.fsync(fd)
            } finally {
                android.system.Os.close(fd)
            }
        }
    }

    private fun extractSignature(zip: ByteArray): ByteArray? {
        val stream = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zip))
        while (true) {
            val entry = stream.nextEntry ?: return null
            if (entry.name == KeyboardPackageVerifier.SIGNATURE_ENTRY) {
                return stream.readBytes()
            }
        }
    }

    private fun failed(code: KeyboardUpdateErrorCode, detail: String): InstallResult {
        recordFailure(code, detail)
        return InstallResult.Fail(code, detail)
    }

    private fun recordFailure(code: KeyboardUpdateErrorCode, detail: String) {
        state.put(STATE_UPDATE_STATE, "FAILED")
        state.put(STATE_LAST_ERROR_CODE, code.name)
        state.put(STATE_LAST_ERROR_MESSAGE, detail.take(200))
    }

    private fun recordSuccess(
        manifest: KeyboardManifest,
        signed: Boolean,
        alreadyCurrent: Boolean,
        confirmed: Boolean = false,
    ) {
        state.put(STATE_UPDATE_STATE, if (alreadyCurrent) "READY" else "ACTIVE")
        state.put(STATE_SOURCE_TYPE, KeyboardSource.ACTIVE_VERSION.name)
        state.put(STATE_KEYBOARD_VERSION, manifest.keyboardVersion)
        state.put(STATE_MIN_NATIVE_API, manifest.minNativeApi.toString())
        state.put(STATE_REQUIRED_CAPABILITIES, manifest.requiredCapabilities.joinToString(","))
        state.put(STATE_KEY_ID, manifest.keyId)
        state.put(STATE_CONTENT_HASH, manifest.contentHash)
        state.put(STATE_LAST_SUCCESS_AT, clock().toString())
        state.put(STATE_SIGNED, signed.toString())
        state.put(STATE_SIGNATURE_CONFIRMED, confirmed.toString())
    }

    companion object {
        const val UNSIGNED_MARKER = ".unsigned"

        /** 用户确认签名不符后导入的版本标记（docs/design/userdata.md §3）。
         * 与 `.unsigned`（调试免签）不同：目录里是真签名、只是验不过。 */
        const val SIGNATURE_CONFIRMED_MARKER = ".signature-confirmed"
        const val STATE_UPDATE_URL = "update_url"

        /** The saved metainfo.json source - one stable address
         * that resolves to the current zip on every check. */
        const val STATE_SOURCE_URL = "update_source_url"
        const val STATE_SOURCE_TYPE = "source_type"
        const val STATE_KEYBOARD_VERSION = "keyboard_version"
        const val STATE_MIN_NATIVE_API = "min_native_api"
        const val STATE_REQUIRED_CAPABILITIES = "required_capabilities"
        const val STATE_KEY_ID = "key_id"
        const val STATE_CONTENT_HASH = "content_hash"
        const val STATE_UPDATE_STATE = "update_state"
        const val STATE_LAST_ATTEMPT_AT = "last_attempt_at"
        const val STATE_LAST_SUCCESS_AT = "last_success_at"
        const val STATE_LAST_ERROR_CODE = "last_error_code"
        const val STATE_LAST_ERROR_MESSAGE = "last_error_message"
        const val STATE_SIGNED = "signed"
        const val STATE_SIGNATURE_CONFIRMED = "signature_confirmed"
    }
}

/** Reuses the verifier's manifest rules without re-verifying a ZIP. */
internal object KeyboardManifestRecovery {
    sealed class Outcome {
        class Ok(val manifest: KeyboardManifest) : Outcome()
        object Err : Outcome()
    }

    fun parse(bytes: ByteArray): Outcome {
        val root = try {
            CanonicalJson.parseAndCheck(bytes)
        } catch (exception: CanonicalJson.FormatException) {
            return Outcome.Err
        }
        if (root !is CanonicalJson.Value.Obj) return Outcome.Err
        val keyboardVersion = (root["keyboardVersion"] as? CanonicalJson.Value.Str)?.value
            ?: return Outcome.Err
        if (!KeyboardPackageVerifier.SEMVER.matches(keyboardVersion)) return Outcome.Err
        val minNativeApi = (root["minNativeApi"] as? CanonicalJson.Value.Num)?.value
            ?: return Outcome.Err
        val capsArray = root["requiredCapabilities"] as? CanonicalJson.Value.Arr ?: return Outcome.Err
        val caps = capsArray.items.map { (it as? CanonicalJson.Value.Str)?.value ?: return Outcome.Err }
        val keyId = (root["keyId"] as? CanonicalJson.Value.Str)?.value ?: return Outcome.Err
        val payloadObj = root["payload"] as? CanonicalJson.Value.Obj ?: return Outcome.Err
        if (payloadObj.keys.sorted() != KeyboardPackageVerifier.PAYLOAD_ENTRIES.sorted()) {
            return Outcome.Err
        }
        val payload = HashMap<String, Pair<String, Long>>()
        for ((name, value) in payloadObj.entries) {
            val meta = value as? CanonicalJson.Value.Obj ?: return Outcome.Err
            val hash = (meta["sha256"] as? CanonicalJson.Value.Str)?.value ?: return Outcome.Err
            val length = (meta["bytes"] as? CanonicalJson.Value.Num)?.value ?: return Outcome.Err
            payload[name] = hash to length
        }
        return Outcome.Ok(
            KeyboardManifest(
                formatVersion = 1,
                keyboardVersion = keyboardVersion,
                minNativeApi = minNativeApi,
                maxTestedNativeApi = (root["maxTestedNativeApi"] as? CanonicalJson.Value.Num)?.value,
                requiredCapabilities = caps,
                keyId = keyId,
                payload = payload,
                rawBytes = bytes,
                contentHash = KeyboardPackageVerifier.sha256Hex(bytes),
            ),
        )
    }
}