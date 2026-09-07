package com.feelime.ime.update

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounded reader for keyboard packages selected from a document provider.
 * Keeping the limit at the stream boundary avoids loading an arbitrary local
 * file before [KeyboardPackageVerifier] can reject it. */
object KeyboardPackageReader {
    const val MAX_BYTES: Long = KeyboardPackageVerifier.MAX_ZIP_BYTES

    sealed class Result {
        class Ok(val bytes: ByteArray) : Result()
        object TooLarge : Result()
    }

    fun read(input: InputStream, maxBytes: Long = MAX_BYTES): Result {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > maxBytes) return Result.TooLarge
                output.write(buffer, 0, count)
            }
        }
        return Result.Ok(output.toByteArray())
    }
}
