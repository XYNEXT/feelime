package com.feelime.ime.update

import org.junit.Test
import java.text.Normalizer

class DebugNormalizerTest {
    private fun cps(s: String) = s.map { Integer.toHexString(it.code) }.joinToString(" ")

    @Test
    fun dump() {
        val pre = "keybo\u00e1rd.js"
        val dec = "keybo\u0061\u0301rd.js"
        val nfc = Normalizer.normalize(dec, Normalizer.Form.NFC)
        println("equal: " + (pre == nfc))
        val zip = TestZipBuilder()
            .add(pre, ByteArray(1))
            .add(dec, ByteArray(1))
            .build()
        val result = KeyboardPackageVerifier(KeyboardPackageFixture.releaseKeys()).verify(zip)
        println("result: " + result)
        if (result is KeyboardPackageVerifier.Result.Rejected) {
            println("code: " + result.code + " detail: " + result.detail)
        }
        org.junit.Assert.assertTrue(result is KeyboardPackageVerifier.Result.Rejected)
    }
}
