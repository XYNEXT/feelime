package com.feelime.ime.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeyboardActivationRecoveryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class MapState : KeyboardStateStore {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    }

    private class CrashAt(vararg points: String) : UpdateFaults {
        private val hit = mutableSetOf<String>()
        private val targets = points.toSet()
        var crashed = false
            private set
        override fun at(point: String) {
            if (point in targets && point !in hit) {
                hit.add(point)
                crashed = true
                throw SimulatedCrash(point)
            }
        }
        class SimulatedCrash(point: String) : RuntimeException("crash at $point")
    }

    private val roots = mutableListOf<File>()

    private fun builtIn(version: String = "2.0.0") = object : BuiltInKeyboardSource {
        override fun version() = version
        override fun files(): Map<String, ByteArray> = mapOf(
            "index.html" to "<html>built-in $version</html>".toByteArray(),
            "keyboard.css" to "body{}".toByteArray(),
            "keyboard.js" to "var v='$version';".toByteArray(),
            "VERSION" to version.toByteArray(),
        )
    }

    private fun newStore(
        root: File = tmp.newFolder().also(roots::add),
        state: MapState = MapState(),
        faults: UpdateFaults = UpdateFaults.None,
    ): Pair<KeyboardStore, File> {
        val store = KeyboardStore(
            root = root,
            builtIn = builtIn(),
            verifier = KeyboardPackageVerifier(KeyboardPackageFixture.releaseKeys()),
            state = state,
            clock = { 1_700_000_000_000 },
            faults = faults,
        )
        return store to root
    }

    private fun zip(version: String = "2.0.1") = KeyboardPackageFixture.build(version = version)

    private fun expectedHash(version: String): String {
        val files = KeyboardPackageFixture.payloadFiles(version)
        return KeyboardPackageVerifier.sha256Hex(KeyboardPackageFixture.manifest(files, version = version))
    }

    @Test
    fun freshStartResolvesBuiltInAndSeedPersists() {
        val (store, _) = newStore()
        val active = store.resolve()
        assertEquals(KeyboardSource.BUILT_IN, active.source)
        assertEquals("2.0.0", active.version)
        assertTrue(File(active.dir, "index.html").isFile)
        assertEquals("built-in:2.0.0", active.revision)
    }

    @Test
    fun sameVersionStaleBuiltInCopyIsReseededFromApk() {
        // Regression: an APK upgrade whose VERSION asset stayed put used to
        // keep serving the previously seeded (stale) keyboard forever.
        val (store, testRoot) = newStore()
        store.resolve()
        File(testRoot, "built-in/keyboard.js").writeText("var v='stale';")
        val active = store.resolve()
        assertEquals(KeyboardSource.BUILT_IN, active.source)
        assertEquals("var v='2.0.0';", active.dir.resolve("keyboard.js").readText())
    }

    @Test
    fun installActivatesNewVersionAndKeepsPreviousRollback() {
        val state = MapState()
        val (store, _) = newStore(state = state)
        assertTrue(store.install(zip("2.0.1")) is KeyboardStore.InstallResult.Ok)
        assertTrue(store.install(zip("2.0.2")) is KeyboardStore.InstallResult.Ok)
        val active = store.resolve()
        assertEquals("2.0.2", active.version)
        assertEquals(KeyboardSource.ACTIVE_VERSION, active.source)
        assertTrue(active.signed)
        assertEquals("ACTIVE", state.map[KeyboardStore.STATE_UPDATE_STATE])

        // Tamper with the active version: resolve must roll back to previous.
        File(active.dir, "keyboard.js").writeText("evil")
        val rolled = store.resolve()
        assertEquals("2.0.1", rolled.version)
        assertEquals("ROLLED_BACK", state.map[KeyboardStore.STATE_UPDATE_STATE])

        // Delete previous too: built-in keyboard still available.
        File(rolled.dir, "index.html").delete()
        val fallback = store.resolve()
        assertEquals(KeyboardSource.BUILT_IN, fallback.source)
    }

    @Test
    fun overwriteInstallResidueOlderHotUpdateFallsBackToApkKeyboard() {
        // After an overwrite-install the stale active
        // pointer (hot-update OLDER than the fresh APK keyboard) shadowed
        // the built-in forever - the stale keyboard's pre-v2 handshake can
        // never complete against the new native (rejected on purpose,
        // ), so the IME came up dead: no pinyin, no tools.
        val state = MapState()
        val testRoot = tmp.newFolder().also(roots::add)
        val oldApk = KeyboardStore(
            root = testRoot,
            builtIn = builtIn("2.0.0"),
            verifier = KeyboardPackageVerifier(KeyboardPackageFixture.releaseKeys()),
            state = state,
            clock = { 1_700_000_000_000 },
        )
        assertTrue(oldApk.install(zip("2.0.1")) is KeyboardStore.InstallResult.Ok)
        // APK upgraded in place: same files dir (root) and same prefs (state),
        // newer bundled keyboard.
        val upgraded = KeyboardStore(
            root = testRoot,
            builtIn = builtIn("2.2.0"),
            verifier = KeyboardPackageVerifier(KeyboardPackageFixture.releaseKeys()),
            state = state,
            clock = { 1_700_000_000_000 },
        )
        val active = upgraded.resolve()
        assertEquals(KeyboardSource.BUILT_IN, active.source)
        assertEquals("2.2.0", active.version)
        assertEquals("built-in:2.2.0", active.revision)
        assertEquals("BUILT_IN", state.map[KeyboardStore.STATE_UPDATE_STATE])
        assertEquals("BUILT_IN", state.map[KeyboardStore.STATE_SOURCE_TYPE])
        // The pointer itself is written back empty (the swap, not
        // just the re-derived result - a later resolve must not even reach
        // the residue branch).
        val pointerJson = File(testRoot, "active.json").readText()
        assertTrue("active.json written back empty, got: $pointerJson",
            "\"active\":\"\"" in pointerJson.replace(" ", ""))
        assertEquals("built-in:2.2.0", upgraded.resolve().revision)
    }

    @Test
    fun newerHotUpdateSurvivesApkUpgrade() {
        // A hot-update AHEAD of the freshly installed built-in is the user's
        // deliberate choice to run ahead - the upgrade must keep serving it.
        val state = MapState()
        val testRoot = tmp.newFolder().also(roots::add)
        val oldApk = KeyboardStore(
            root = testRoot,
            builtIn = builtIn("2.0.0"),
            verifier = KeyboardPackageVerifier(KeyboardPackageFixture.releaseKeys()),
            state = state,
            clock = { 1_700_000_000_000 },
        )
        assertTrue(oldApk.install(zip("2.5.0")) is KeyboardStore.InstallResult.Ok)
        val upgraded = KeyboardStore(
            root = testRoot,
            builtIn = builtIn("2.2.0"),
            verifier = KeyboardPackageVerifier(KeyboardPackageFixture.releaseKeys()),
            state = state,
            clock = { 1_700_000_000_000 },
        )
        val active = upgraded.resolve()
        assertEquals(KeyboardSource.ACTIVE_VERSION, active.source)
        assertEquals("2.5.0", active.version)
    }

    @Test
    fun preReleaseStaleHotUpdateStillFallsBackToApkKeyboard() {
        // Review F2: 3.20.0-rc1 must not survive an APK upgrade just because
        // the numeric compare cannot parse the suffix.
        val state = MapState()
        val testRoot = tmp.newFolder().also(roots::add)
        val oldApk = KeyboardStore(
            root = testRoot,
            builtIn = builtIn("2.0.0"),
            verifier = KeyboardPackageVerifier(KeyboardPackageFixture.releaseKeys()),
            state = state,
            clock = { 1_700_000_000_000 },
        )
        assertTrue(oldApk.install(zip("2.0.1")) is KeyboardStore.InstallResult.Ok)
        val upgraded = KeyboardStore(
            root = testRoot,
            builtIn = builtIn("2.2.0+build.5"),
            verifier = KeyboardPackageVerifier(KeyboardPackageFixture.releaseKeys()),
            state = state,
            clock = { 1_700_000_000_000 },
        )
        val active = upgraded.resolve()
        assertEquals(KeyboardSource.BUILT_IN, active.source)
        assertEquals("2.2.0+build.5", active.version)
    }

    @Test
    fun overwriteInstallResidueUnloadableActiveFallsBackToApkKeyboard() {
        // Review F6: the residue branch's active==null side - a corrupted
        // (unloadable) active version with no previous still hands back the
        // APK keyboard, and the dead pointer is NOT resurrected.
        val state = MapState()
        val testRoot = tmp.newFolder().also(roots::add)
        val oldApk = KeyboardStore(
            root = testRoot,
            builtIn = builtIn("2.0.0"),
            verifier = KeyboardPackageVerifier(KeyboardPackageFixture.releaseKeys()),
            state = state,
            clock = { 1_700_000_000_000 },
        )
        assertTrue(oldApk.install(zip("2.0.1")) is KeyboardStore.InstallResult.Ok)
        // Corrupt the installed version so loadVersion rejects it.
        val versions = File(testRoot, "keyboard/versions")
        versions.listFiles()?.firstOrNull()?.let { File(it, "index.html").delete() }
        val upgraded = KeyboardStore(
            root = testRoot,
            builtIn = builtIn("2.2.0"),
            verifier = KeyboardPackageVerifier(KeyboardPackageFixture.releaseKeys()),
            state = state,
            clock = { 1_700_000_000_000 },
        )
        val active = upgraded.resolve()
        assertEquals(KeyboardSource.BUILT_IN, active.source)
        assertEquals("2.2.0", active.version)
        assertEquals("built-in:2.2.0", upgraded.resolve().revision)
    }

    @Test
    fun failureAtEveryCheckpointAlwaysLeavesAKeyboard() {
        val checkpoints = listOf(
            "staging-write", "before-rename", "after-rename",
            "before-pointer", "pointer-write-mid", "pointer-write-after", "after-pointer",
        )
        for (point in checkpoints) {
            val root = tmp.newFolder().also(roots::add)
            // A prior good install gives "previous" something to roll back to.
            newStore(root = root).first.install(zip("2.0.1"))
            val faults = CrashAt(point)
            val (subject, _) = newStore(root = root, faults = faults)
            try {
                subject.install(zip("2.0.2"))
            } catch (crash: CrashAt.SimulatedCrash) {
                // process death at this checkpoint
            }
            assertTrue("crash must have fired for $point", faults.crashed)

            val (recovery, _) = newStore(root = root)
            val recovered = recovery.resolve()
            assertTrue(
                "recovered version must be new/previous/built-in, got ${recovered.version}",
                recovered.version in setOf("2.0.0", "2.0.1", "2.0.2"),
            )
            assertTrue(File(recovered.dir, "index.html").isFile)
            assertTrue(File(recovered.dir, "keyboard.js").isFile)
        }
    }

    @Test
    fun corruptedPointerFallsBackToBuiltIn() {
        val (store, root) = newStore()
        store.install(zip("2.0.1"))
        File(root, "active.json").writeText("{not json")
        val (recovery, _) = newStore(root = root)
        assertEquals(KeyboardSource.BUILT_IN, recovery.resolve().source)
    }

    @Test
    fun restoreBuiltInSwitchesPointerWithoutDeletingVersions() {
        val (store, root) = newStore()
        store.install(zip("2.0.1"))
        assertTrue(store.restoreBuiltIn())
        assertEquals(KeyboardSource.BUILT_IN, store.resolve().source)
        assertTrue("rollback versions must survive", File(root, "versions").listFiles()!!.isNotEmpty())
        assertFalse(store.restoreBuiltIn())
    }

    @Test
    fun restoreBuiltInAfterTwoInstallsReachesTheApkKeyboard() {
        // Regression: with a previous hot-update still on disk the
        // rollback branch used to shadow seedBuiltIn FOREVER - "restore
        // built-in" kept serving the older hot-update (a real device sat on
        // a stale control layer this way) and a reinstalled APK keyboard was
        // unreachable without deleting app data.
        val (store, _) = newStore()
        assertTrue(store.install(zip("2.0.1")) is KeyboardStore.InstallResult.Ok)
        assertTrue(store.install(zip("2.0.2")) is KeyboardStore.InstallResult.Ok)
        assertTrue(store.restoreBuiltIn())
        val active = store.resolve()
        assertEquals(KeyboardSource.BUILT_IN, active.source)
        assertEquals("2.0.0", active.version)
    }

    @Test
    fun handshakeCleanupKeepsActiveAndPrevious() {
        val (store, root) = newStore()
        store.install(zip("2.0.1"))
        store.install(zip("2.0.2"))
        store.install(zip("2.0.3"))
        store.onHandshakeComplete()
        val kept = File(root, "versions").listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf(expectedHash("2.0.2"), expectedHash("2.0.3")).sorted(), kept)
        val active = store.resolve()
        assertEquals(expectedHash("2.0.3"), active.contentHash)
    }

    @Test
    fun idempotentReinstallReturnsAlreadyCurrent() {
        val (store, _) = newStore()
        assertTrue(store.install(zip("2.0.1")) is KeyboardStore.InstallResult.Ok)
        val again = store.install(zip("2.0.1"))
        assertTrue(again is KeyboardStore.InstallResult.Ok)
        assertTrue((again as KeyboardStore.InstallResult.Ok).alreadyCurrent)
    }

    @Test
    fun tamperedStoredSignatureRejectedAtStartup() {
        val (store, root) = newStore()
        store.install(zip("2.0.1"))
        val active = store.resolve()
        assertEquals(KeyboardSource.ACTIVE_VERSION, active.source)
        val sig = File(active.dir, KeyboardPackageVerifier.SIGNATURE_ENTRY)
        sig.writeBytes(ByteArray(64))
        val (recovery, _) = newStore(root = root)
        assertEquals(KeyboardSource.BUILT_IN, recovery.resolve().source)
    }

    @Test
    fun debugUnsignedVersionResolvesOnlyWhenAllowed() {
        val root = tmp.newFolder().also(roots::add)
        val unsigned = KeyboardPackageFixture.build(signed = false)
        fun store(allow: Boolean): KeyboardStore {
            val state = MapState()
            return KeyboardStore(
                root = root,
                builtIn = builtIn(),
                verifier = KeyboardPackageVerifier(
                    KeyboardPackageFixture.releaseKeys(),
                    allowUnsignedDebug = true,
                ),
                state = state,
                allowUnsignedVersions = allow,
            )
        }
        val installer = store(allow = true)
        assertTrue(installer.install(unsigned) is KeyboardStore.InstallResult.Ok)
        val active = installer.resolve()
        assertEquals(KeyboardSource.ACTIVE_VERSION, active.source)
        assertFalse(active.signed)
        // Startup without the debug flag must not activate the unsigned version.
        assertEquals(KeyboardSource.BUILT_IN, store(allow = false).resolve().source)
    }

    @Test
    fun incompatibleManifestRejectedWithStatePersisted() {
        val state = MapState()
        val (store, _) = newStore(state = state)
        val future = KeyboardPackageFixture.build(minNativeApi = 99)
        val result = store.install(future)
        assertTrue(result is KeyboardStore.InstallResult.Fail)
        assertEquals(
            KeyboardUpdateErrorCode.COMPAT_MIN_NATIVE_API,
            (result as KeyboardStore.InstallResult.Fail).code,
        )
        assertEquals("FAILED", state.map[KeyboardStore.STATE_UPDATE_STATE])
        assertEquals("COMPAT_MIN_NATIVE_API", state.map[KeyboardStore.STATE_LAST_ERROR_CODE])

        val pinned = store.install(zip(), fragmentPin = "deadbeef")
        assertEquals(
            KeyboardUpdateErrorCode.FRAGMENT_PIN_MISMATCH,
            (pinned as KeyboardStore.InstallResult.Fail).code,
        )
    }
}
