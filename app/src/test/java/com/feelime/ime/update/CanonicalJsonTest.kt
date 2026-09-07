package com.feelime.ime.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalJsonTest {
    private fun obj(vararg pairs: Pair<String, CanonicalJson.Value>) =
        CanonicalJson.Value.Obj(pairs.toList())

    private fun str(value: String) = CanonicalJson.Value.Str(value)
    private fun num(value: Long) = CanonicalJson.Value.Num(value)

    @Test
    fun serializesKeysSortedAndEscapesMinimally() {
        val value = obj(
            "payload" to obj("z" to num(1), "a" to num(2)),
            "formatVersion" to num(1),
            "note" to str("a\"b\\c\nd"),
        )
        assertEquals(
            """{"formatVersion":1,"note":"a\"b\\c\nd","payload":{"a":2,"z":1}}""",
            CanonicalJson.serialize(value).decodeToString(),
        )
    }

    @Test
    fun roundTripsCanonicalInput() {
        val canonical = """{"a":[1,2,{"b":"ü"}],"c":"café"}""".toByteArray()
        val parsed = CanonicalJson.parseAndCheck(canonical)
        assertArrayEquals(canonical, CanonicalJson.serialize(parsed))
    }

    @Test
    fun rejectsNonCanonicalEncodings() {
        assertThrows(CanonicalJson.FormatException::class.java) {
            CanonicalJson.parseAndCheck("""{ "a":1 }""".toByteArray())
        }
        assertThrows(CanonicalJson.FormatException::class.java) {
            CanonicalJson.parseAndCheck("﻿{\"a\":1}".toByteArray()) // BOM
        }
        assertThrows(CanonicalJson.FormatException::class.java) {
            CanonicalJson.parseAndCheck("""{"a":1.5}""".toByteArray())
        }
        assertThrows(CanonicalJson.FormatException::class.java) {
            CanonicalJson.parseAndCheck("""{"a":01}""".toByteArray())
        }
        assertThrows(CanonicalJson.FormatException::class.java) {
            CanonicalJson.parseAndCheck("""{"a":1,"a":2}""".toByteArray())
        }
        assertThrows(CanonicalJson.FormatException::class.java) {
            CanonicalJson.parseAndCheck("""{"a":1} """.toByteArray())
        }
        assertThrows(CanonicalJson.FormatException::class.java) {
            CanonicalJson.parseAndCheck("""{"a":+1}""".toByteArray())
        }
        assertThrows(CanonicalJson.FormatException::class.java) {
            CanonicalJson.parseAndCheck("""{"a":"x"}""".toByteArray())
        }
    }

    @Test
    fun rejectsInvalidUtf8AndControlBytes() {
        assertThrows(CanonicalJson.FormatException::class.java) {
            CanonicalJson.parseAndCheck(
                "{\"a\":\"".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "\"}".toByteArray(),
            )
        }
        assertThrows(CanonicalJson.FormatException::class.java) {
            CanonicalJson.parseAndCheck(byteArrayOf('{'.code.toByte(), 0x01, '}'.code.toByte()))
        }
    }
}
