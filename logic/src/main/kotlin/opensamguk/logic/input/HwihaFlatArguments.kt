package opensamguk.logic.input

import kotlinx.serialization.json.*

/** Retains decoded keys before insertion, so even escaped duplicate keys cannot overwrite. */
internal class HwihaFlatArguments(private val raw: String) {
    private var at = 0
    private fun spaces() { while (at < raw.length && raw[at] in " \t\r\n") at++ }
    private fun expect(char: Char) {
        spaces()
        require(at < raw.length && raw[at] == char)
        at++
    }
    private fun stringToken(): String {
        spaces()
        require(at < raw.length && raw[at] == '"')
        val start = at++
        while (at < raw.length) {
            when (raw[at++]) {
                '\\' -> { require(at < raw.length); at++ }
                '"' -> return raw.substring(start, at)
            }
        }
        throw IllegalArgumentException("unterminated string")
    }
    fun read(allowIntegerArrays: Boolean = false): Map<String, JsonElement> {
        expect('{')
        val fields = linkedMapOf<String, JsonElement>()
        spaces()
        if (at < raw.length && raw[at] == '}') {
            at++
        } else {
            while (true) {
                val key = (Json.parseToJsonElement(stringToken()) as JsonPrimitive).content
                require(key !in fields) { "duplicate argument" }
                expect(':')
                spaces()
                require(at < raw.length)
                val token = if (raw[at] == '[') {
                    require(allowIntegerArrays)
                    val start = at++
                    while (at < raw.length && raw[at] != ']') {
                        require(raw[at] in "0123456789, \t\r\n")
                        at++
                    }
                    require(at < raw.length)
                    at++
                    raw.substring(start, at)
                } else if (raw[at] == '"') stringToken() else {
                    val start = at
                    while (at < raw.length && raw[at] !in ",} \t\r\n") at++
                    raw.substring(start, at)
                }
                val value = Json.parseToJsonElement(token)
                require(value is JsonPrimitive || (allowIntegerArrays && value is JsonArray))
                fields[key] = value
                spaces()
                require(at < raw.length)
                if (raw[at] == '}') { at++; break }
                expect(',')
            }
        }
        spaces()
        require(at == raw.length)
        return fields
    }
}
