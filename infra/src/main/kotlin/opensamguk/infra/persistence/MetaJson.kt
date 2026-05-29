package opensamguk.infra.persistence

/**
 * jsonb codec for the `meta` columns, byte-faithful to PHP `Json::encode`
 * (`legacy/devsam-core/src/sammo/Json.php:16` — `JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES`):
 *
 * - compact (NO spaces after `:` or `,`)
 * - UTF-8 literal: do NOT \uXXXX-escape non-ASCII (Korean keys/values stay literal)
 * - forward slashes are NOT escaped (`/` emitted as-is)
 * - keys in INSERTION order — backed by a `LinkedHashMap`, never sorted
 * - integers serialize as `5`, never `5.0` (PHP `json_encode` of an int)
 *
 * The G4 byte-comparison against the PHP golden DB dump is the authority.
 */
object MetaJson {

    /** Parse a jsonb string into an insertion-order-preserving `LinkedHashMap`. */
    fun decode(json: String?): MutableMap<String, Any?> {
        if (json == null) return LinkedHashMap()
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return LinkedHashMap()
        val parser = Parser(trimmed)
        val value = parser.parseValue()
        parser.skipWs()
        require(parser.atEnd()) { "trailing content in jsonb: $json" }
        @Suppress("UNCHECKED_CAST")
        return when (value) {
            null -> LinkedHashMap()
            is MutableMap<*, *> -> value as MutableMap<String, Any?>
            else -> error("jsonb root is not an object: $json")
        }
    }

    /** Encode an ordered map to the PHP-faithful compact jsonb string. */
    fun encode(value: Any?): String {
        val sb = StringBuilder()
        writeValue(sb, value)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is String -> writeString(sb, value)
            is Map<*, *> -> writeObject(sb, value)
            is List<*> -> writeArray(sb, value)
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> writeDouble(sb, value)
            is Float -> writeDouble(sb, value.toDouble())
            is Number -> sb.append(value.toString())
            else -> error("unsupported jsonb value type: ${value::class}")
        }
    }

    private fun writeObject(sb: StringBuilder, map: Map<*, *>) {
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            writeString(sb, k.toString())
            sb.append(':')
            writeValue(sb, v)
        }
        sb.append('}')
    }

    private fun writeArray(sb: StringBuilder, list: List<*>) {
        sb.append('[')
        var first = true
        for (v in list) {
            if (!first) sb.append(',')
            first = false
            writeValue(sb, v)
        }
        sb.append(']')
    }

    /**
     * A whole-number `Double` (e.g. `5.0`) is emitted as `5` to mirror PHP, where the
     * value came in as an int. Non-whole doubles use the shortest round-trippable form.
     */
    private fun writeDouble(sb: StringBuilder, d: Double) {
        if (d.isFinite() && d == Math.floor(d)) {
            sb.append(d.toLong().toString())
        } else {
            sb.append(d.toString())
        }
    }

    /**
     * Mirrors PHP `json_encode` string escaping under
     * `JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES`: escape only the JSON-mandatory
     * characters (`"`, `\`, and C0 control chars), leaving `/` and all non-ASCII literal.
     */
    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c < ' ') {
                        sb.append("\\u")
                        sb.append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append('"')
    }

    /** Minimal recursive-descent JSON parser producing LinkedHashMap-backed objects. */
    private class Parser(private val src: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= src.length

        fun skipWs() {
            while (i < src.length && src[i].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) i++
        }

        fun parseValue(): Any? {
            skipWs()
            require(i < src.length) { "unexpected end of jsonb" }
            return when (src[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObject(): MutableMap<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> break
                    else -> error("expected ',' or '}' in object, got '$c'")
                }
            }
            return map
        }

        private fun parseArray(): MutableList<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> break
                    else -> error("expected ',' or ']' in array, got '$c'")
                }
            }
            return list
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> break
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = src.substring(i, i + 4)
                            i += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> error("invalid escape '\\$e'")
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseBool(): Boolean {
            return if (src.startsWith("true", i)) { i += 4; true }
            else if (src.startsWith("false", i)) { i += 5; false }
            else error("invalid literal at $i")
        }

        private fun parseNull(): Any? {
            require(src.startsWith("null", i)) { "invalid literal at $i" }
            i += 4
            return null
        }

        private fun parseNumber(): Number {
            val start = i
            if (peek() == '-') i++
            while (i < src.length && src[i] in '0'..'9') i++
            var isDouble = false
            if (i < src.length && src[i] == '.') {
                isDouble = true; i++
                while (i < src.length && src[i] in '0'..'9') i++
            }
            if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                isDouble = true; i++
                if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
                while (i < src.length && src[i] in '0'..'9') i++
            }
            val token = src.substring(start, i)
            return if (isDouble) token.toDouble()
            else token.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: token.toDouble()
        }

        private fun peek(): Char = if (i < src.length) src[i] else ' '
        private fun next(): Char {
            require(i < src.length) { "unexpected end of jsonb" }
            return src[i++]
        }
        private fun expect(c: Char) {
            val got = next()
            require(got == c) { "expected '$c' but got '$got' at ${i - 1}" }
        }
    }
}
