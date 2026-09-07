package com.feelime.ime.nativeengine

import java.io.ByteArrayOutputStream

internal data class ProtoField(
    val number: Int,
    val wireType: Int,
    val integer: Long? = null,
    val bytes: ByteArray? = null,
    val group: List<ProtoField>? = null,
) {
    fun text(): String? = bytes?.toString(Charsets.UTF_8)
}

internal object ProtoWire {
    fun command(input: ByteArray): ByteArray = message { bytes(1, input) }

    fun createSession(): ByteArray = command(message {
        integer(1, 1)
        // Capability.text_deletion = DELETE_PRECEDING_TEXT, required for a
        // mobile client to receive an explicit deletion range on undo.
        bytes(7, message { integer(1, 1) })
        bytes(9, clientRequest())
    })

    fun setRequest(): ByteArray = command(message {
        integer(1, 17)
        bytes(9, clientRequest())
    })

    fun deleteSession(id: Long): ByteArray = command(message {
        integer(1, 2)
        integer(2, id)
    })

    fun sendCharacter(id: Long, codePoint: Int): ByteArray = command(message {
        integer(1, 3)
        integer(2, id)
        bytes(3, message { integer(1, codePoint.toLong()) })
        integer(14, 0)
    })

    fun sendSpecial(id: Long, specialKey: Int): ByteArray = command(message {
        integer(1, 3)
        integer(2, id)
        bytes(3, message { integer(3, specialKey.toLong()) })
        integer(14, 0)
    })

    fun sendSessionCommand(
        id: Long,
        type: Int,
        candidateId: Long? = null,
        precedingText: String? = null,
    ): ByteArray = command(message {
        integer(1, 5)
        integer(2, id)
        bytes(4, message {
            integer(1, type.toLong())
            candidateId?.let { integer(2, it) }
        })
        precedingText?.let { bytes(6, message { string(1, it) }) }
        bytes(7, message { integer(1, 1) })
    })

    private fun clientRequest(): ByteArray = message {
        // DEFAULT_TABLE, a stable name for touch usage metadata, and a bounded
        // mobile candidate page.  These are fields 4, 7, and 15 of Request.
        integer(4, 0)
        string(7, "QWERTY_KANA")
        integer(15, 9)
        integer(16, 64)
    }

    fun fields(data: ByteArray): List<ProtoField> = Reader(data).readMessage()

    fun output(command: ByteArray): List<ProtoField> =
        fields(command).firstOrNull { it.number == 2 }?.bytes?.let(::fields).orEmpty()

    fun integer(fields: List<ProtoField>, number: Int): Long? =
        fields.firstOrNull { it.number == number }?.integer

    fun nested(fields: List<ProtoField>, number: Int): List<ProtoField> =
        fields.firstOrNull { it.number == number }?.bytes?.let(::fields).orEmpty()

    private fun message(block: Writer.() -> Unit): ByteArray =
        Writer().apply(block).toByteArray()

    private class Writer {
        private val output = ByteArrayOutputStream()

        fun integer(number: Int, value: Long) {
            varint((number shl 3).toLong())
            varint(value)
        }

        fun bytes(number: Int, value: ByteArray) {
            varint(((number shl 3) or 2).toLong())
            varint(value.size.toLong())
            output.write(value)
        }

        fun string(number: Int, value: String) = bytes(number, value.toByteArray(Charsets.UTF_8))

        private fun varint(initial: Long) {
            var value = initial
            while (true) {
                if (value and -0x80L == 0L) {
                    output.write(value.toInt())
                    return
                }
                output.write((value.toInt() and 0x7f) or 0x80)
                value = value ushr 7
            }
        }

        fun toByteArray(): ByteArray = output.toByteArray()
    }

    private class Reader(private val data: ByteArray) {
        private var position = 0

        fun readMessage(endGroup: Int? = null): List<ProtoField> {
            val result = mutableListOf<ProtoField>()
            while (position < data.size) {
                val tag = varint()
                val number = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                if (wire == 4) {
                    require(endGroup == number) { "unexpected protobuf end group $number" }
                    return result
                }
                result += when (wire) {
                    0 -> ProtoField(number, wire, integer = varint())
                    1 -> ProtoField(number, wire, bytes = take(8))
                    2 -> ProtoField(number, wire, bytes = take(varint().toInt()))
                    3 -> ProtoField(number, wire, group = readMessage(number))
                    5 -> ProtoField(number, wire, bytes = take(4))
                    else -> error("unsupported protobuf wire type $wire")
                }
            }
            require(endGroup == null) { "unterminated protobuf group $endGroup" }
            return result
        }

        private fun varint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                require(position < data.size) { "truncated protobuf varint" }
                val byte = data[position++].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            error("oversized protobuf varint")
        }

        private fun take(length: Int): ByteArray {
            require(length >= 0 && position + length <= data.size) { "truncated protobuf field" }
            return data.copyOfRange(position, position + length).also { position += length }
        }
    }
}
