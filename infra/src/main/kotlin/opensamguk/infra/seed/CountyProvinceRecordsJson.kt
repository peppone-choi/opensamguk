package opensamguk.infra.seed

import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/** Only the county projection survives parsing; unused terrain is validated without materializing it. */
internal object CountyProvinceRecordsJson {
    data class Province(val isObject: Boolean, val id: String? = null, val jurisdictionId: String? = null)

    fun read(bytes: ByteArray): List<Province>? = Reader(bytes).document()

    private class Reader(bytes: ByteArray) {
        // Decode UTF-8 incrementally with the same replacement behavior as ByteArray.toString(UTF_8).
        // Trim only document edges (MetaJson.trim), not whitespace inside JSON containers.
        private val start = edge(bytes, true)
        private val source = InputStreamReader(ByteArrayInputStream(bytes, start,
            (edge(bytes, false) - start).coerceAtLeast(0)), Charsets.UTF_8)
        private val buffer = CharArray(4096)
        private var cursor = 0
        private var size = 0
        private var position = 0

        fun document(): List<Province>? {
            if (peek() == null) return null
            val objectRoot = peek() == '{'
            var records: List<Province>? = null
            if (objectRoot) {
                objectFields { key ->
                    if (key == "provinceRecords") records = provinces() else value()
                }
            } else value()
            whitespace()
            require(peek() == null) { "trailing content in jsonb: [redacted]" }
            if (!objectRoot) {
                // A null root is treated as an empty object by MetaJson.
                requireRootNull()
            }
            return records
        }

        private var rootWasNull = false
        private fun requireRootNull() {
            check(rootWasNull) { "jsonb root is not an object: [redacted]" }
        }

        private fun provinces(): List<Province>? {
            whitespace()
            if (peek() != '[') { value(); return null }
            next()
            val result = ArrayList<Province>()
            whitespace()
            if (peek() == ']') { next(); return result }
            while (true) {
                whitespace()
                if (peek() == '{') {
                    var id: String? = null
                    var jurisdiction: String? = null
                    objectFields { key ->
                        when (key) {
                            "id" -> id = stringValue()
                            "jurisdictionId" -> jurisdiction = stringValue()
                            else -> value()
                        }
                    }
                    result.add(Province(true, id, jurisdiction))
                } else { value(); result.add(Province(false)) }
                whitespace()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> return result
                    else -> error("expected ',' or ']' in array, got '$c'")
                }
            }
        }

        private fun stringValue(): String? {
            whitespace()
            if (peek() == '"') return string(true)
            value()
            return null
        }

        private fun objectFields(field: (String) -> Unit) {
            expect('{')
            whitespace()
            if (peek() == '}') { next(); return }
            while (true) {
                whitespace()
                val key = string(true)!!
                whitespace()
                expect(':')
                field(key)
                whitespace()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return
                    else -> error("expected ',' or '}' in object, got '$c'")
                }
            }
        }

        private fun value() {
            whitespace()
            require(peek() != null) { "unexpected end of jsonb" }
            when (peek()) {
                '{' -> skipObject()
                '[' -> skipArray()
                '"' -> string(false)
                't', 'f' -> boolean()
                'n' -> {
                    val start = position
                    for (c in "null") require(nextOrNull() == c) { "invalid literal at $start" }
                    if (start == 0) rootWasNull = true
                }
                else -> number()
            }
        }

        private fun skipObject() {
            next()
            whitespace()
            if (peek() == '}') { next(); return }
            while (true) {
                whitespace()
                string(false)
                whitespace()
                expect(':')
                value()
                whitespace()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return
                    else -> error("expected ',' or '}' in object, got '$c'")
                }
            }
        }

        private fun skipArray() {
            next()
            whitespace()
            if (peek() == ']') { next(); return }
            while (true) {
                value()
                whitespace()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> return
                    else -> error("expected ',' or ']' in array, got '$c'")
                }
            }
        }

        private fun string(capture: Boolean): String? {
            expect('"')
            val out = if (capture) StringBuilder() else null
            while (true) {
                when (val c = next()) {
                    '"' -> return out?.toString()
                    '\\' -> {
                        val decoded = when (val escape = next()) {
                            '"', '\\', '/' -> escape
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> unicode()
                            else -> error("invalid escape '\\$escape'")
                        }
                        out?.append(decoded)
                    }
                    else -> out?.append(c)
                }
            }
        }

        private fun unicode(): Char {
            val start = position
            val hex = CharArray(4)
            for (index in hex.indices) {
                hex[index] = nextOrNull() ?: throw StringIndexOutOfBoundsException(
                    "Range [$start, ${start + 4}) out of bounds for length $position")
            }
            // Keep MetaJson's signed radix conversion, including \u+041 and \u-001.
            return String(hex).toInt(16).toChar()
        }

        private fun boolean() {
            val start = position
            val literal = if (peek() == 't') "true" else "false"
            for (c in literal) check(nextOrNull() == c) { "invalid literal at $start" }
        }

        private fun number() {
            if (peek() == '-') next()
            var digits = digits()
            if (peek() == '.') { next(); digits += digits() }
            var exponentDigits = true
            if (peek() == 'e' || peek() == 'E') {
                next()
                if (peek() == '+' || peek() == '-') next()
                exponentDigits = digits() > 0
            }
            // MetaJson scans precisely this grammar before Long/Double conversion. Valid unused
            // tokens need no Number or substring; even overflow is accepted as a Double infinity.
            if (digits == 0 || !exponentDigits) throw NumberFormatException("invalid jsonb number: [redacted]")
        }

        private fun digits(): Int {
            var count = 0
            while (peek()?.let { it in '0'..'9' } == true) { next(); count++ }
            return count
        }

        private fun whitespace() {
            while (true) {
                when (peek()) {
                    ' ', '\t', '\n', '\r' -> next()
                    else -> return
                }
            }
        }

        private fun expect(expected: Char) {
            val got = next()
            require(got == expected) { "expected '$expected' but got '$got' at ${position - 1}" }
        }

        private fun peek(): Char? {
            if (cursor == size) {
                size = source.read(buffer)
                cursor = 0
                if (size < 0) { size = 0; return null }
            }
            return buffer[cursor]
        }

        private fun nextOrNull(): Char? = peek()?.also { cursor++; position++ }
        private fun next(): Char = nextOrNull() ?: throw IllegalArgumentException("unexpected end of jsonb")
    }

    private fun edge(bytes: ByteArray, start: Boolean): Int {
        var boundary = if (start) 0 else bytes.size
        while (if (start) boundary < bytes.size else boundary > 0) {
            var first = if (start) boundary else boundary - 1
            when (bytes[first].toInt()) {
                9, 10, 11, 12, 13, 28, 29, 30, 31, 32 -> {
                    boundary += if (start) 1 else -1
                    continue
                }
            }
            if (!start) while (first > 0 && bytes[first].toInt() and 0xc0 == 0x80) first--
            var end = if (start) first + 1 else boundary
            if (start) while (end < bytes.size && bytes[end].toInt() and 0xc0 == 0x80) end++
            val character = String(bytes, first, end - first, Charsets.UTF_8)
            if (character.length != 1 || !character[0].isWhitespace()) break
            boundary = if (start) end else first
        }
        return boundary
    }
}
