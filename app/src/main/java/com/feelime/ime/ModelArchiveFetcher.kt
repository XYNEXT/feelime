package com.feelime.ime

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.HashSet
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Downloads one fixed model archive and extracts exactly one safe tar entry.
 *
 * The archive is kept as a hidden partial file so an interrupted transfer can
 * resume with HTTP Range. It is deleted only after the extracted target has
 * passed both the entry and final-file checks. The parser walks the complete
 * tar stream even after finding the target, which lets it reject duplicate or
 * unsafe names before a model is sealed.
 */
internal class ModelArchiveFetcher(
    private val connectionFactory: ModelConnectionFactory,
    private val allowHttp: Boolean,
    private val isCancelled: () -> Boolean,
    private val checkNetwork: () -> Unit = {},
    private val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {
    fun fetch(
        archive: ModelArchiveSpec,
        destination: File,
        archivePart: File = defaultArchivePart(destination),
        onProgress: (downloadedBytes: Long) -> Unit = {},
    ) {
        require(archive.verified) { "归档清单缺少完整校验信息" }
        require(maxEntryBytes > 0 && maxTotalBytes > 0) { "归档解压大小限制无效" }
        validateEntryPath(archive.entry)
        if (!allowHttp && URI.create(archive.url).scheme.equals("http", ignoreCase = true)) {
            throw IOException("不允许的模型下载协议：http")
        }

        archivePart.parentFile?.mkdirs()
        var offset = prepareArchivePart(archive, archivePart)
        onProgress(offset)
        if (offset < archive.bytes) {
            downloadArchive(archive, archivePart, offset, onProgress, allowRestart = true)
            offset = archivePart.length()
        }
        if (offset != archive.bytes) {
            throw IOException("下载不完整：归档 (${offset}/${archive.bytes})")
        }
        verifyArchive(archive, archivePart)
        extractEntry(archive, archivePart, destination)
        if (!archivePart.delete() && archivePart.exists()) {
            throw IOException("归档清理失败：${archivePart.name}")
        }
    }

    private fun prepareArchivePart(archive: ModelArchiveSpec, part: File): Long {
        if (isCancelled()) throw ModelDownloadCancelled()
        if (!part.isFile) return 0L
        val length = part.length()
        if (length > archive.bytes) {
            part.delete()
            return 0L
        }
        if (length == archive.bytes && sha256(part) == archive.sha256) return length
        if (length == archive.bytes) {
            part.delete()
            return 0L
        }
        return length
    }

    private fun downloadArchive(
        archive: ModelArchiveSpec,
        part: File,
        offset: Long,
        onProgress: (downloadedBytes: Long) -> Unit,
        allowRestart: Boolean,
    ) {
        if (isCancelled()) throw ModelDownloadCancelled()
        checkNetwork()
        val connection = connectionFactory.open(URI.create(archive.url), offset)
        try {
            val code = connection.responseCode
            val append = when (code) {
                HTTP_OK -> false
                HTTP_PARTIAL -> offset > 0
                HTTP_RANGE_NOT_SATISFIABLE -> {
                    if (offset > 0 && allowRestart) {
                        part.delete()
                        downloadArchive(archive, part, 0L, onProgress, allowRestart = false)
                        return
                    }
                    throw IOException("归档 Range 无效")
                }
                else -> throw IOException("HTTP $code for ${archive.url}")
            }
            writeArchiveBody(
                input = connection.body(),
                part = part,
                append = append,
                resumeOffset = if (append) offset else 0L,
                expectedBytes = archive.bytes,
                onProgress = onProgress,
            )
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun writeArchiveBody(
        input: InputStream,
        part: File,
        append: Boolean,
        resumeOffset: Long,
        expectedBytes: Long,
        onProgress: (downloadedBytes: Long) -> Unit,
    ) {
        FileOutputStream(part, append).use { output ->
            input.use { stream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var written = resumeOffset
                while (true) {
                    if (isCancelled()) throw ModelDownloadCancelled()
                    checkNetwork()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (written > expectedBytes - count) {
                        throw IOException("归档下载超过清单大小")
                    }
                    output.write(buffer, 0, count)
                    written += count
                    onProgress(written)
                }
            }
        }
    }

    private fun verifyArchive(archive: ModelArchiveSpec, part: File) {
        if (sha256(part) != archive.sha256) {
            part.delete()
            throw IOException("归档内容校验失败")
        }
    }

    private fun extractEntry(
        archive: ModelArchiveSpec,
        part: File,
        destination: File,
    ) {
        val destinationPart = File(
            destination.parentFile ?: File("."),
            "${destination.name}.extract.part",
        )
        destination.parentFile?.mkdirs()
        destinationPart.delete()
        var found = false
        var totalBytes = 0L
        val names = HashSet<String>()
        try {
            BufferedInputStream(part.inputStream(), BUFFER_SIZE).use { raw ->
                BZip2CompressorInputStream(raw, true).use { bzip ->
                    TarArchiveInputStream(bzip).use { tar ->
                        while (true) {
                            if (isCancelled()) throw ModelDownloadCancelled()
                            val entry = tar.nextEntry ?: break
                            val rawName = entry.name ?: throw IOException("归档条目没有名称")
                            // Tar directory entries conventionally carry one trailing slash.
                            // Normalize that marker before validating and de-duplicating names;
                            // file entries still go through the strict path validator unchanged.
                            val name = normalizeEntryPath(rawName, entry.isDirectory)
                            if (!names.add(name)) {
                                throw IOException("归档包含重复条目：$name")
                            }
                            val size = entry.size
                            if (size < 0 || size > maxEntryBytes) {
                                throw IOException("归档条目过大：$name")
                            }
                            if (size > maxTotalBytes - totalBytes) {
                                throw IOException("归档解压总大小超过限制")
                            }
                            totalBytes += size
                            if (name == archive.entry) {
                                if (!entry.isFile || entry.isSymbolicLink || entry.isLink) {
                                    throw IOException("归档目标不是普通文件：$name")
                                }
                                if (found) throw IOException("归档包含重复目标：$name")
                                if (size != archive.entryBytes) {
                                    throw IOException("归档目标大小不匹配：$name")
                                }
                                found = true
                                FileOutputStream(destinationPart).use { output ->
                                    copyEntry(tar, output, size)
                                }
                            } else {
                                discardEntry(tar, size)
                            }
                        }
                    }
                }
            }
            if (!found) throw IOException("归档缺少目标条目：${archive.entry}")
            if (destinationPart.length() != archive.entryBytes ||
                sha256(destinationPart) != archive.entrySha256
            ) {
                throw IOException("归档目标内容校验失败：${archive.entry}")
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("无法替换模型文件：${destination.name}")
            }
            if (!destinationPart.renameTo(destination)) {
                throw IOException("模型文件写入失败：${destination.name}")
            }
        } catch (failure: Exception) {
            destinationPart.delete()
            throw failure
        }
    }

    private fun copyEntry(input: InputStream, output: FileOutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            if (isCancelled()) throw ModelDownloadCancelled()
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val count = input.read(buffer, 0, requested)
            if (count < 0) throw IOException("归档目标内容提前结束")
            if (count == 0) continue
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun discardEntry(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            if (isCancelled()) throw ModelDownloadCancelled()
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val count = input.read(buffer, 0, requested)
            if (count < 0) throw IOException("归档条目内容提前结束")
            if (count == 0) continue
            remaining -= count
        }
    }

    private fun validateEntryPath(path: String) {
        if (path.isEmpty() || path.length > MAX_PATH_LENGTH || path.indexOf('\u0000') >= 0 ||
            path.contains('\\') || path.startsWith('/') || path.matches(DRIVE_PATH)
        ) {
            throw IOException("归档包含不安全路径：$path")
        }
        val components = path.split('/')
        if (components.any { it.isEmpty() || it == "." || it == ".." }) {
            throw IOException("归档包含不安全路径：$path")
        }
    }

    private fun normalizeEntryPath(path: String, isDirectory: Boolean): String {
        val normalized = if (isDirectory && path.endsWith('/')) {
            path.dropLast(1)
        } else {
            path
        }
        validateEntryPath(normalized)
        return normalized
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                if (isCancelled()) throw ModelDownloadCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_PATH_LENGTH = 4096
        const val DEFAULT_MAX_ENTRY_BYTES = 512L * 1024 * 1024
        const val DEFAULT_MAX_TOTAL_BYTES = 512L * 1024 * 1024
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        val DRIVE_PATH = Regex("^[A-Za-z]:.*")

        fun defaultArchivePart(destination: File): File =
            File(destination.parentFile ?: File("."), ".${destination.name}.archive.part")
    }
}
