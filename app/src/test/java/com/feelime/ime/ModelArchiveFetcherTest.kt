package com.feelime.ime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelArchiveFetcherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `downloads archive, extracts exact entry and removes archive cache`() {
        val target = "model/encoder.int8.onnx"
        val expected = "encoder bytes".toByteArray()
        val archiveBytes = tarBz2(
            "model/" to ByteArray(0),
            target to expected,
            "model/other.onnx" to "other".toByteArray(),
        )
        val factory = ArchiveFactory(archiveBytes)
        val destination = File(tmp.root, "encoder.int8.onnx")
        val progress = mutableListOf<Long>()

        fetcher(factory).fetch(
            archive = archive(archiveBytes, target, expected),
            destination = destination,
            onProgress = progress::add,
        )

        assertArrayEquals(expected, destination.readBytes())
        assertFalse(defaultPart(destination).exists())
        assertEquals(0L, factory.opened.single().second)
        assertEquals(archiveBytes.size.toLong(), progress.last())
    }

    @Test
    fun `resumes archive partial with range`() {
        val target = "model/encoder.onnx"
        val expected = ByteArray(4096) { (it % 251).toByte() }
        val archiveBytes = tarBz2(target to expected)
        val destination = File(tmp.root, "encoder.onnx")
        val part = defaultPart(destination)
        val offset = archiveBytes.size / 3
        part.writeBytes(archiveBytes.copyOf(offset))
        val factory = ArchiveFactory(archiveBytes)

        fetcher(factory).fetch(archive(archiveBytes, target, expected), destination)

        assertEquals(offset.toLong(), factory.opened.single().second)
        assertArrayEquals(expected, destination.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun `restarts once when a stale range is rejected`() {
        val target = "model/encoder.onnx"
        val expected = "payload".toByteArray()
        val archiveBytes = tarBz2(target to expected)
        val destination = File(tmp.root, "encoder.onnx")
        val part = defaultPart(destination)
        val offset = archiveBytes.size / 2
        part.writeBytes(archiveBytes.copyOf(offset))
        val factory = RangeRejectingFactory(archiveBytes)

        fetcher(factory).fetch(archive(archiveBytes, target, expected), destination)

        assertEquals(listOf(offset.toLong(), 0L), factory.ranges)
        assertArrayEquals(expected, destination.readBytes())
    }

    @Test
    fun `does not keep a complete archive with a wrong archive hash`() {
        val target = "model/encoder.onnx"
        val expected = "payload".toByteArray()
        val archiveBytes = tarBz2(target to expected)
        val destination = File(tmp.root, "encoder.onnx")
        val spec = archive(archiveBytes, target, expected).copy(sha256 = "0".repeat(64))

        val error = runCatching { fetcher(ArchiveFactory(archiveBytes)).fetch(spec, destination) }
            .exceptionOrNull()

        assertTrue(error is IOException)
        assertFalse(destination.exists())
        assertFalse(defaultPart(destination).exists())
    }

    @Test
    fun `rejects an extracted entry whose final hash does not match`() {
        val target = "model/encoder.onnx"
        val expected = "payload".toByteArray()
        val archiveBytes = tarBz2(target to expected)
        val destination = File(tmp.root, "encoder.onnx")
        val spec = archive(archiveBytes, target, expected).copy(entrySha256 = "0".repeat(64))

        val error = runCatching { fetcher(ArchiveFactory(archiveBytes)).fetch(spec, destination) }
            .exceptionOrNull()

        assertTrue(error is IOException)
        assertFalse(destination.exists())
        assertTrue(defaultPart(destination).exists())
    }

    @Test
    fun `rejects unsafe path before writing outside destination`() {
        val target = "model/encoder.onnx"
        val expected = "payload".toByteArray()
        val archiveBytes = tarBz2(
            "../escape" to "bad".toByteArray(),
            target to expected,
        )
        val destination = File(tmp.root, "encoder.onnx")

        val error = runCatching {
            fetcher(ArchiveFactory(archiveBytes)).fetch(archive(archiveBytes, target, expected), destination)
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertFalse(destination.exists())
        assertFalse(File(tmp.root.parentFile, "escape").exists())
        // The verified archive remains resumable after an extraction failure.
        assertTrue(defaultPart(destination).exists())
    }

    @Test
    fun `rejects duplicate tar entries`() {
        val target = "model/encoder.onnx"
        val expected = "payload".toByteArray()
        val archiveBytes = tarBz2(target to expected, target to expected)
        val destination = File(tmp.root, "encoder.onnx")

        val error = runCatching {
            fetcher(ArchiveFactory(archiveBytes)).fetch(archive(archiveBytes, target, expected), destination)
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertFalse(destination.exists())
    }

    @Test
    fun `limits each extracted entry`() {
        val target = "model/encoder.onnx"
        val expected = ByteArray(32) { 7 }
        val archiveBytes = tarBz2(target to expected)
        val destination = File(tmp.root, "encoder.onnx")

        val error = runCatching {
            ModelArchiveFetcher(
                connectionFactory = ArchiveFactory(archiveBytes),
                allowHttp = true,
                isCancelled = { false },
                maxEntryBytes = 8,
                maxTotalBytes = 128,
            ).fetch(archive(archiveBytes, target, expected), destination)
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertFalse(destination.exists())
    }

    private fun fetcher(factory: ModelConnectionFactory) = ModelArchiveFetcher(
        connectionFactory = factory,
        allowHttp = true,
        isCancelled = { false },
    )

    private fun archive(bytes: ByteArray, target: String, expected: ByteArray) = ModelArchiveSpec(
        url = "https://example.test/mobile.tar.bz2",
        bytes = bytes.size.toLong(),
        sha256 = sha256(bytes),
        entry = target,
        entryBytes = expected.size.toLong(),
        entrySha256 = sha256(expected),
    )

    private fun defaultPart(destination: File) =
        File(destination.parentFile, ".${destination.name}.archive.part")

    private class ArchiveFactory(private val bytes: ByteArray) : ModelConnectionFactory {
        val opened = mutableListOf<Pair<String, Long>>()

        override fun open(url: URI, rangeFrom: Long): ModelConnection {
            opened += url.toString() to rangeFrom
            val start = rangeFrom.toInt()
            val response = if (rangeFrom > 0) 206 else 200
            return object : ModelConnection {
                override val responseCode: Int = response
                override fun body(): InputStream =
                    ByteArrayInputStream(bytes.copyOfRange(start, bytes.size))

                override fun disconnect() = Unit
            }
        }
    }

    private class RangeRejectingFactory(private val bytes: ByteArray) : ModelConnectionFactory {
        val ranges = mutableListOf<Long>()

        override fun open(url: URI, rangeFrom: Long): ModelConnection {
            ranges += rangeFrom
            return object : ModelConnection {
                override val responseCode: Int = if (rangeFrom > 0) 416 else 200
                override fun body(): InputStream = ByteArrayInputStream(
                    if (rangeFrom > 0) ByteArray(0) else bytes,
                )

                override fun disconnect() = Unit
            }
        }
    }

    private fun tarBz2(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        BZip2CompressorOutputStream(output).use { bzip ->
            TarArchiveOutputStream(bzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for ((name, bytes) in entries) {
                    val entry = TarArchiveEntry(name)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
