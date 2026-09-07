package com.feelime.ime.update

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Raw ZIP writer for verifier tests: unlike java.util.zip.ZipOutputStream it
 * can emit duplicate entries, arbitrary names (NUL/backslash/traversal),
 * symlink modes, wrong declared sizes, and unsupported methods.
 */
class TestZipBuilder {
    class Entry(
        val name: ByteArray,
        val data: ByteArray,
        val method: Int = 8,
        val unixMode: Int = 0x81A4,
        val declaredUncompressed: Long? = null,
        val declaredCompressed: Long? = null,
    )

    private val entries = mutableListOf<Entry>()

    fun add(
        name: String,
        data: ByteArray,
        method: Int = 8,
        unixMode: Int = 0x81A4,
        declaredUncompressed: Long? = null,
    ): TestZipBuilder {
        entries.add(Entry(name.toByteArray(Charsets.UTF_8), data, method, unixMode, declaredUncompressed, null))
        return this
    }

    fun addRawName(name: ByteArray, data: ByteArray, unixMode: Int = 0x81A4): TestZipBuilder {
        entries.add(Entry(name, data, 8, unixMode, null, null))
        return this
    }

    fun addRaw(entry: Entry): TestZipBuilder {
        entries.add(entry)
        return this
    }

    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        var offset = 0
        for (entry in entries) {
            val compressed = if (entry.method == 8) deflate(entry.data) else entry.data
            val crc = CRC32().apply { update(entry.data) }.value
            val uncomp = entry.declaredUncompressed ?: entry.data.size.toLong()
            val comp = entry.declaredCompressed ?: compressed.size.toLong()
            out.write(le32(0x04034b50))
            out.write(le16(20))
            out.write(le16(0))
            out.write(le16(entry.method))
            out.write(le16(0))
            out.write(le16(0))
            out.write(le32(crc.toInt()))
            out.write(le32(comp.toInt()))
            out.write(le32(uncomp.toInt()))
            out.write(le16(entry.name.size))
            out.write(le16(0))
            out.write(entry.name)
            out.write(compressed)
            central.write(le32(0x02014b50))
            central.write(le16(0x031E))
            central.write(le16(20))
            central.write(le16(0))
            central.write(le16(entry.method))
            central.write(le16(0))
            central.write(le16(0))
            central.write(le32(crc.toInt()))
            central.write(le32(comp.toInt()))
            central.write(le32(uncomp.toInt()))
            central.write(le16(entry.name.size))
            central.write(le16(0))
            central.write(le16(0))
            central.write(le16(0))
            central.write(le16(0))
            central.write(le32(((entry.unixMode.toLong() and 0xFFFF) shl 16).toInt()))
            central.write(le32(offset))
            central.write(entry.name)
            offset += 30 + entry.name.size + compressed.size
        }
        val centralBytes = central.toByteArray()
        out.write(centralBytes)
        out.write(le32(0x06054b50))
        out.write(le16(0))
        out.write(le16(0))
        out.write(le16(entries.size))
        out.write(le16(entries.size))
        out.write(le32(centralBytes.size))
        out.write(le32(offset))
        out.write(le16(0))
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val buffer = ByteArray(4096)
        val out = ByteArrayOutputStream()
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun le16(value: Int) = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
    )

    private fun le32(value: Int) = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte(),
    )
}
