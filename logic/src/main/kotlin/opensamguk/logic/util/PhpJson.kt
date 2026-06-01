package opensamguk.logic.util

/**
 * Logic-side byte-faithful `Json::encode`/`Json::decode` (PHP `sammo\Json`,
 * `JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES`). Mirrors the infra `MetaJson` codec but lives in
 * `:logic` (no infra dep) so the message / auction / betting DTO `#[JsonString]` payloads are encoded
 * byte-identically to the PHP golden:
 *  - compact (NO spaces after `:`/`,`)
 *  - UTF-8 literal (non-ASCII NOT \uXXXX-escaped)
 *  - `/` NOT escaped
 *  - keys in INSERTION order (LinkedHashMap-backed, never sorted)
 *  - whole-number doubles serialize as ints (`5`, not `5.0`)
 */
fun jsonEncode(value: Any?): String {
    val sb = StringBuilder()
    writeJsonValue(sb, value)
    return sb.toString()
}

/** Parse a json string into an insertion-order-preserving structure (object → LinkedHashMap). */
@Suppress("UNCHECKED_CAST")
fun jsonDecode(json: String?): Map<String, Any?> {
    if (json.isNullOrBlank()) return LinkedHashMap()
    val parser = JsonParser(json.trim())
    val v = parser.parseValue()
    return (v as? Map<String, Any?>) ?: LinkedHashMap()
}

/** Parse a json string whose root may be an array or object (returns the raw decoded value). */
fun jsonDecodeAny(json: String?): Any? {
    if (json.isNullOrBlank()) return null
    return JsonParser(json.trim()).parseValue()
}

private const val FORM_FEED = '\u000c'

private fun writeJsonValue(sb: StringBuilder, value: Any?) {
    when (value) {
        null -> sb.append("null")
        is Boolean -> sb.append(if (value) "true" else "false")
        is String -> writeJsonString(sb, value)
        is Map<*, *> -> {
            sb.append('{')
            var first = true
            for ((k, v) in value) {
                if (!first) sb.append(','); first = false
                writeJsonString(sb, k.toString()); sb.append(':'); writeJsonValue(sb, v)
            }
            sb.append('}')
        }
        is List<*> -> {
            sb.append('[')
            var first = true
            for (v in value) { if (!first) sb.append(','); first = false; writeJsonValue(sb, v) }
            sb.append(']')
        }
        is Int, is Long, is Short, is Byte -> sb.append(value.toString())
        is Double -> if (value.isFinite() && value == Math.floor(value)) sb.append(value.toLong().toString()) else sb.append(value.toString())
        is Float -> writeJsonValue(sb, value.toDouble())
        is Number -> sb.append(value.toString())
        else -> error("unsupported json value type: ${value::class}")
    }
}

private fun writeJsonString(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\b' -> sb.append("\\b")
            FORM_FEED -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') { sb.append("\\u"); sb.append(c.code.toString(16).padStart(4, '0')) } else sb.append(c)
        }
    }
    sb.append('"')
}

private class JsonParser(private val src: String) {
    private var i = 0

    fun parseValue(): Any? {
        skipWs()
        return when (src[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBool()
            'n' -> { i += 4; null }
            else -> parseNumber()
        }
    }

    private fun parseObject(): MutableMap<String, Any?> {
        i++ // {
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (src[i] == '}') { i++; return map }
        while (true) {
            skipWs(); val key = parseString(); skipWs(); i++ // :
            map[key] = parseValue(); skipWs()
            when (src[i++]) { ',' -> continue; '}' -> break; else -> error("bad object") }
        }
        return map
    }

    private fun parseArray(): MutableList<Any?> {
        i++ // [
        val list = ArrayList<Any?>()
        skipWs()
        if (src[i] == ']') { i++; return list }
        while (true) {
            list.add(parseValue()); skipWs()
            when (src[i++]) { ',' -> continue; ']' -> break; else -> error("bad array") }
        }
        return list
    }

    private fun parseString(): String {
        i++ // "
        val sb = StringBuilder()
        while (true) {
            val c = src[i++]
            when (c) {
                '"' -> break
                '\\' -> when (val e = src[i++]) {
                    '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                    'b' -> sb.append('\b'); 'f' -> sb.append(FORM_FEED); 'n' -> sb.append('\n')
                    'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    'u' -> { sb.append(src.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> error("bad escape \\$e")
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun parseBool(): Boolean =
        if (src.startsWith("true", i)) { i += 4; true } else { i += 5; false }

    private fun parseNumber(): Number {
        val start = i
        if (src[i] == '-') i++
        while (i < src.length && src[i] in '0'..'9') i++
        var isDouble = false
        if (i < src.length && src[i] == '.') { isDouble = true; i++; while (i < src.length && src[i] in '0'..'9') i++ }
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            isDouble = true; i++; if (src[i] == '+' || src[i] == '-') i++; while (i < src.length && src[i] in '0'..'9') i++
        }
        val token = src.substring(start, i)
        return if (isDouble) token.toDouble()
        else token.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: token.toDouble()
    }

    private fun skipWs() { while (i < src.length && (src[i] == ' ' || src[i] == '\t' || src[i] == '\n' || src[i] == '\r')) i++ }
}
