package opensamguk.logic.input

import kotlinx.serialization.json.*

/** Flat enlistment arguments only. Actor identity always comes from the authenticated caller. */
object HwihaEnlistmentInput {
    fun parse(actorId: Int, rawJson: String?): EnlistmentRequest? {
        if (actorId <= 0 || rawJson == null) return null
        return try {
            val fields = FlatArguments(rawJson).read()
            val modeValue = fields["mode"] as? JsonPrimitive ?: return null
            if (!modeValue.isString) return null
            val mode = EnlistmentMode.entries.firstOrNull { it.name == modeValue.content } ?: return null
            if (mode == EnlistmentMode.RANDOM) {
                if (fields.keys != setOf("mode")) return null
                EnlistmentRequest(actorId, mode)
            } else {
                if (fields.keys != setOf("mode", "targetId")) return null
                val target = fields["targetId"] as? JsonPrimitive ?: return null
                if (target.isString || !Regex("[1-9][0-9]*").matches(target.content)) return null
                val id = target.content.toIntOrNull() ?: return null
                EnlistmentRequest(actorId, mode, id)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun canonicalJson(request: EnlistmentRequest): String {
        require(request.actorId > 0)
        require(if (request.mode == EnlistmentMode.RANDOM) request.targetId == null else (request.targetId ?: 0) > 0)
        return buildJsonObject {
            put("mode", request.mode.name)
            request.targetId?.let { put("targetId", it) }
        }.toString()
    }

    /** Retains decoded keys before insertion, so even escaped duplicate keys cannot overwrite. */
    private class FlatArguments(private val raw: String) {
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
        fun read(): Map<String, JsonElement> {
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
                    val token = if (raw[at] == '"') stringToken() else {
                        val start = at
                        while (at < raw.length && raw[at] !in ",} \t\r\n") at++
                        raw.substring(start, at)
                    }
                    val value = Json.parseToJsonElement(token)
                    require(value is JsonPrimitive)
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
}
