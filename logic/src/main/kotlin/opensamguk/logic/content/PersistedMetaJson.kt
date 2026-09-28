package opensamguk.logic.content

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** KV values are JSON text in the current tick and decoded maps after a cold load. */
object PersistedMetaJson {
    fun raw(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        else -> element(value).toString()
    }

    private fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Map<*, *> -> buildJsonObject {
            value.forEach { (key, nested) ->
                require(key is String) { "meta object key must be a string" }
                put(key, element(nested))
            }
        }
        is Iterable<*> -> buildJsonArray { value.forEach { add(element(it)) } }
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> throw IllegalArgumentException("unsupported meta value type: ${value::class.simpleName}")
    }
}
