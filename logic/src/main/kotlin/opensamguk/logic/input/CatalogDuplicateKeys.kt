package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** Detects duplicate object keys before kotlinx JsonObject can overwrite an earlier value. */
internal class HwihaCatalogDuplicateKeys(private val raw: String) {
    private var at = 0

    fun check() {
        value()
        spaces()
        require(at == raw.length) { "trailing catalog content" }
    }

    private fun value() {
        spaces()
        require(at < raw.length) { "missing JSON value" }
        when (raw[at]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> stringToken()
            else -> {
                val start = at
                while (at < raw.length && raw[at] !in ",}] \t\r\n") at++
                require(at > start) { "missing JSON atom" }
                Json.parseToJsonElement(raw.substring(start, at))
            }
        }
    }

    private fun objectValue() {
        expect('{')
        val keys = mutableSetOf<String>()
        spaces()
        if (peek('}')) { at++; return }
        while (true) {
            val key = Json.parseToJsonElement(stringToken()).jsonPrimitive.content
            require(keys.add(key)) { "duplicate catalog key: $key" }
            expect(':')
            value()
            spaces()
            if (peek('}')) { at++; return }
            expect(',')
        }
    }

    private fun arrayValue() {
        expect('[')
        spaces()
        if (peek(']')) { at++; return }
        while (true) {
            value()
            spaces()
            if (peek(']')) { at++; return }
            expect(',')
        }
    }

    private fun stringToken(): String {
        spaces()
        require(peek('"')) { "expected JSON string" }
        val start = at++
        while (at < raw.length) {
            when (raw[at++]) {
                '\\' -> { require(at < raw.length) { "incomplete escape" }; at++ }
                '"' -> return raw.substring(start, at)
            }
        }
        throw IllegalArgumentException("unterminated JSON string")
    }

    private fun spaces() { while (at < raw.length && raw[at] in " \t\r\n") at++ }
    private fun peek(char: Char) = at < raw.length && raw[at] == char
    private fun expect(char: Char) {
        spaces()
        require(peek(char)) { "expected '$char'" }
        at++
    }
}
