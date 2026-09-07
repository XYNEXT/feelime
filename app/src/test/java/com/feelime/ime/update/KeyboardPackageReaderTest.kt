package com.feelime.ime.update

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPackageReaderTest {
    @Test
    fun readsSmallLocalPackageBytes() {
        val bytes = "local-keyboard-zip".toByteArray()
        val result = KeyboardPackageReader.read(ByteArrayInputStream(bytes))

        assertTrue(result is KeyboardPackageReader.Result.Ok)
        assertArrayEquals(bytes, (result as KeyboardPackageReader.Result.Ok).bytes)
    }

    @Test
    fun stopsBeforeLoadingAnOversizedLocalFile() {
        val bytes = ByteArray(17)
        val result = KeyboardPackageReader.read(ByteArrayInputStream(bytes), maxBytes = 16)

        assertTrue(result is KeyboardPackageReader.Result.TooLarge)
    }
}

