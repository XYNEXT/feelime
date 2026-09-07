package com.feelime.ime.update

/**
 * Canonical JSON for the keyboard update manifest (design §8.1). Parsing is
 * strict and round-trip exact: input is accepted only when re-serializing the
 * parsed value reproduces the original bytes, so the Ed25519 signature always
 * covers one unambiguous encoding.
 */
object CanonicalJson {
    class FormatException(message: String) : Exception(message)

    sealed class Value {
        data class Str(val value: String) : Value()
        data class Num(val value: Long) : Value()
        data class Arr(val items: List<Value>) : Value()
        data class Obj(val entries: List<Pair<String, Value>>) : Value() {
            private val index = entries.toMap()
            operator fun get(key: String): Value? = index[key]
            val keys get() = entries.map { it.first }
        }
    }

    fun parseAndCheck(bytes: ByteArray): Value {
        val value = Parser(bytes).parseRoot()
        val canonical = serialize(value)
        if (!canonical.contentEquals(bytes)) {
            throw FormatException("manifest is not canonical JSON")
        }
        return value
    }

    fun serialize(value: Value): ByteArray {
        val out = StringBuilder()
        write(value, out)
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun write(value: Value, out: StringBuilder) {
        when (value) {
            is Value.Str -> writeString(value.value, out)
            is Value.Num -> out.append(value.value.toString())
            is Value.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is Value.Obj -> {
                out.append('{')
                // String.compareTo walks UTF-16 code units, matching JCS key order.
                val sorted = value.entries.sortedWith(compareBy { it.first })
                sorted.forEachIndexed { index, (key, item) ->
                    if (index > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(item, out)
                }
                out.append('}')
            }
        }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (char in value) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\b' -> out.append("\\b")
                char == '\u000C' -> out.append("\\f")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' -> out.append("\\u%04x".format(char.code))
                else -> out.append(char)
            }
        }
        out.append('"')
    }

    private class Parser(private val bytes: ByteArray) {
        private var pos = 0

        fun parseRoot(): Value {
            val value = parseValue()
            skipWhitespace()
            requireEof()
            return value
        }

        private fun parseValue(): Value {
            skipWhitespace()
            return when (val c = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Value.Str(parseString())
                in '0'..'9', '-' -> parseNumber()
                else -> throw FormatException("unexpected character '${c}'")
            }
        }

        private fun parseObject(): Value.Obj {
            expect('{')
            val entries = mutableListOf<Pair<String, Value>>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return Value.Obj(entries.toList())
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw FormatException("object key must be a string")
                val key = parseString()
                if (entries.any { it.first == key }) throw FormatException("duplicate object key")
                skipWhitespace()
                expect(':')
                entries.add(key to parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return Value.Obj(entries.toList())
                    }
                    else -> throw FormatException("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): Value.Arr {
            expect('[')
            val items = mutableListOf<Value>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return Value.Arr(items.toList())
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return Value.Arr(items.toList())
                    }
                    else -> throw FormatException("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (pos >= bytes.size) throw FormatException("unterminated string")
                val c = bytes[pos++]
                val u = c.toInt() and 0xff
                when {
                    u == '"'.code -> return out.toString()
                    u == '\\'.code -> {
                        if (pos >= bytes.size) throw FormatException("unterminated escape")
                        when (val escape = bytes[pos++]) {
                            '"'.code.toByte() -> out.append('"')
                            '\\'.code.toByte() -> out.append('\\')
                            '/'.code.toByte() -> out.append('/')
                            'b'.code.toByte() -> out.append('\b')
                            'f'.code.toByte() -> out.append('\u000C')
                            'n'.code.toByte() -> out.append('\n')
                            'r'.code.toByte() -> out.append('\r')
                            't'.code.toByte() -> out.append('\t')
                            'u'.code.toByte() -> {
                                if (pos + 4 > bytes.size) throw FormatException("bad \\u escape")
                                val hex = String(bytes, pos, 4, Charsets.US_ASCII)
                                val code = hex.toIntOrNull(16) ?: throw FormatException("bad \\u escape")
                                if (!hex.lowercase().all { it in "0123456789abcdef" }) {
                                    throw FormatException("\\u escape must be lowercase hex")
                                }
                                pos += 4
                                // Reject unpaired surrogates; accept pairs only.
                                if (code in 0xD800..0xDFFF) throw FormatException("surrogate in \\u escape")
                                out.append(code.toChar())
                            }
                            else -> throw FormatException("unknown escape '\\${escape}'")
                        }
                    }
                    u < 0x20 -> throw FormatException("raw control character in string")
                    u < 0x80 -> out.append(u.toChar())
                    else -> decodeUtf8(out)
                }
            }
        }

        private fun decodeUtf8(out: StringBuilder) {
            pos-- // re-read the lead byte
            val b1 = bytes[pos++].toInt() and 0xff
            val (count, code) = when {
                b1 and 0xE0 == 0xC0 -> 1 to (b1 and 0x1F)
                b1 and 0xF0 == 0xE0 -> 2 to (b1 and 0x0F)
                b1 and 0xF8 == 0xF0 -> 3 to (b1 and 0x07)
                else -> throw FormatException("invalid UTF-8 lead byte")
            }
            if (count == 3 && code <= 0x04) throw FormatException("UTF-8 above U+10FFFF")
            var value = code
            repeat(count) {
                if (pos >= bytes.size) throw FormatException("truncated UTF-8")
                val cont = bytes[pos++].toInt() and 0xff
                if (cont and 0xC0 != 0x80) throw FormatException("invalid UTF-8 continuation")
                value = (value shl 6) or (cont and 0x3F)
            }
            if (count == 1 && value <= 0x7F) throw FormatException("overlong UTF-8")
            if (count == 2 && value <= 0x7FF) throw FormatException("overlong UTF-8")
            if (value in 0xD800..0xDFFF) throw FormatException("UTF-16 surrogate encoded as UTF-8")
            out.append(String(Character.toChars(value)))
        }

        private fun parseNumber(): Value.Num {
            val start = pos
            if (peek() == '-') pos++
            if (pos >= bytes.size || bytes[pos] !in '0'.code.toByte()..'9'.code.toByte()) {
                throw FormatException("invalid number")
            }
            if (bytes[pos] == '0'.code.toByte()) {
                pos++
                if (pos < bytes.size && bytes[pos] in '1'.code.toByte()..'9'.code.toByte()) {
                    throw FormatException("leading zero")
                }
            } else {
                while (pos < bytes.size && bytes[pos] in '0'.code.toByte()..'9'.code.toByte()) pos++
            }
            if (pos < bytes.size && (bytes[pos] == '.'.code.toByte() || bytes[pos] == 'e'.code.toByte() || bytes[pos] == 'E'.code.toByte())) {
                throw FormatException("non-integer number")
            }
            val text = String(bytes, start, pos - start, Charsets.US_ASCII)
            return Value.Num(text.toLongOrNull() ?: throw FormatException("number out of range"))
        }

        private fun peek(): Char {
            if (pos >= bytes.size) throw FormatException("unexpected end of input")
            val c = bytes[pos].toInt()
            if (c < 0x20 || c >= 0x7F) throw FormatException("non-ASCII outside string")
            return c.toChar()
        }

        private fun expect(c: Char) {
            if (pos >= bytes.size || bytes[pos] != c.code.toByte()) throw FormatException("expected '$c'")
            pos++
        }

        private fun skipWhitespace() {
            while (pos < bytes.size && (bytes[pos] == ' '.code.toByte() || bytes[pos] == '\t'.code.toByte() || bytes[pos] == '\n'.code.toByte() || bytes[pos] == '\r'.code.toByte())) pos++
        }

        private fun requireEof() {
            if (pos != bytes.size) throw FormatException("trailing content")
        }
    }
}
