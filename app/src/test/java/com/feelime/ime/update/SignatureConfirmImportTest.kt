package com.feelime.ime.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 签名不符的确认导入（docs/design/userdata.md §3）：
 * verifier 只放行 SIGNATURE_BAD，KeyboardStore 落 `.signature-confirmed`
 * 标记，resolve() 凭标记放行——重启（重新 resolve）后仍在。 */
class SignatureConfirmImportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class MapState : KeyboardStateStore {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    }

    private fun builtIn(version: String = "2.0.0") = object : BuiltInKeyboardSource {
        override fun version() = version
        override fun files(): Map<String, ByteArray> = mapOf(
            "index.html" to "<html>built-in $version</html>".toByteArray(),
            "keyboard.css" to "body{}".toByteArray(),
            "keyboard.js" to "var v='$version';".toByteArray(),
            "VERSION" to version.toByteArray(),
        )
    }

    private fun newStore(root: File = tmp.newFolder(), state: MapState = MapState()) =
        KeyboardStore(
            root = root,
            builtIn = builtIn(),
            verifier = KeyboardPackageVerifier(KeyboardPackageFixture.releaseKeys()),
            state = state,
            clock = { 1_700_000_000_000 },
        )

    /** 用另一个密钥签的包：签名格式合法但验不过 → SIGNATURE_BAD。 */
    private fun foreignSignedZip(version: String = "2.0.1"): ByteArray {
        val files = KeyboardPackageFixture.payloadFiles(version)
        val manifest = KeyboardPackageFixture.manifest(files, version = version)
        val foreignSeed = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val signature = TestEd25519Signer.sign(foreignSeed, manifest)
        return TestZipBuilder()
            .add(KeyboardPackageVerifier.MANIFEST_ENTRY, manifest)
            .add(KeyboardPackageVerifier.SIGNATURE_ENTRY, signature)
            .also { b -> files.forEach { (n, d) -> b.add(n, d) } }
            .build()
    }

    private fun hex(text: String): ByteArray {
        val out = ByteArray(text.length / 2)
        for (index in out.indices) {
            out[index] = text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    @Test
    fun verifierRejectsByDefaultAndAcceptsExplicitly() {
        val verifier = KeyboardPackageVerifier(KeyboardPackageFixture.releaseKeys())
        val zip = foreignSignedZip()
        val rejected = verifier.verify(zip)
        assertTrue(rejected is KeyboardPackageVerifier.Result.Rejected)
        assertEquals(
            KeyboardUpdateErrorCode.SIGNATURE_BAD,
            (rejected as KeyboardPackageVerifier.Result.Rejected).code,
        )
        // 确认通道放行，但 signed=false。
        val accepted = verifier.verify(zip, acceptBadSignature = true)
        assertTrue(accepted is KeyboardPackageVerifier.Result.Ok)
        assertFalse((accepted as KeyboardPackageVerifier.Result.Ok).pkg.signed)
        // KEY_UNKNOWN 不在确认通道内：陌生密钥一律拒绝。
        val unknownKey = KeyboardPackageFixture.build(keyId = "attacker-key")
        assertTrue(verifier.verify(unknownKey, acceptBadSignature = true)
            is KeyboardPackageVerifier.Result.Rejected)
    }

    @Test
    fun defaultInstallFailsWithoutTouchingPointer() {
        val root = tmp.newFolder()
        val store = newStore(root)
        val result = store.install(foreignSignedZip())
        assertTrue(result is KeyboardStore.InstallResult.Fail)
        assertEquals(
            KeyboardUpdateErrorCode.SIGNATURE_BAD,
            (result as KeyboardStore.InstallResult.Fail).code,
        )
        assertEquals(KeyboardSource.BUILT_IN, store.resolve().source)
    }

    @Test
    fun confirmedInstallSurvivesRestartAndFlagsState() {
        val root = tmp.newFolder()
        val state = MapState()
        val store = newStore(root, state)
        val result = store.install(foreignSignedZip(), confirmBadSignature = true)
        assertTrue(result is KeyboardStore.InstallResult.Ok)

        // 当场可用：signed=false、signatureConfirmed=true。
        val active = store.resolve()
        assertEquals("2.0.1", active.version)
        assertFalse(active.signed)
        assertTrue(active.signatureConfirmed)
        assertEquals("true", state.map[KeyboardStore.STATE_SIGNATURE_CONFIRMED])
        assertEquals("false", state.map[KeyboardStore.STATE_SIGNED])

        // 「重启」：新 store 实例重新 resolve（含完整复验链）仍然放行。
        val restarted = newStore(root, MapState().also { it.map.putAll(state.map) })
        val reactivated = restarted.resolve()
        assertEquals("2.0.1", reactivated.version)
        assertTrue(reactivated.signatureConfirmed)
    }

    @Test
    fun removingMarkerRestoresTheSafetyChain() {
        val root = tmp.newFolder()
        val store = newStore(root)
        assertTrue(store.install(foreignSignedZip(), confirmBadSignature = true)
            is KeyboardStore.InstallResult.Ok)
        // 标记被抹掉（或未来撤销信任）：resolve 必须退回内置键盘。
        File(root, "versions").listFiles()?.forEach { dir ->
            File(dir, KeyboardStore.SIGNATURE_CONFIRMED_MARKER).delete()
        }
        assertEquals(KeyboardSource.BUILT_IN, store.resolve().source)
    }

    /** URL 带 #sha256= 钉扎时，确认重装必须重放钉扎：验签先于钉扎检查，
     * 不重放会让钉扎不匹配的包借确认通道绕过钉扎（userdata.md §3）。 */
    @Test
    fun confirmInstallStillHonorsTheFragmentPin() {
        val zip = foreignSignedZip()
        val sha = KeyboardPackageVerifier.sha256Hex(zip)
        val correct = newStore().install(zip, fragmentPin = sha, confirmBadSignature = true)
        assertTrue(correct is KeyboardStore.InstallResult.Ok)

        val rejected = newStore().install(zip, fragmentPin = "deadbeef", confirmBadSignature = true)
        assertTrue(rejected is KeyboardStore.InstallResult.Fail)
        assertEquals(
            KeyboardUpdateErrorCode.FRAGMENT_PIN_MISMATCH,
            (rejected as KeyboardStore.InstallResult.Fail).code,
        )
    }
}
