package opensamguk.logic.office

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import opensamguk.common.world.WorldId
import opensamguk.logic.input.CatalogDuplicateKeys

/** The OFFICE branch of the existing reply input, bound to the full observed native source. */
data class OfficeOfferResponseCommand(
    val expectedWorldId: WorldId,
    val expectedOffer: OfficeAppointmentOffer,
    val accepted: Boolean,
) {
    fun canonicalArguments(): String = buildJsonObject {
        put("expectedWorldId", JsonPrimitive(expectedWorldId.value))
        put("expectedOffer", jsonValue(expectedOffer.toMetaValue()))
        put("accepted", JsonPrimitive(accepted))
    }.toString()

    companion object {
        const val INPUT_ID = "court.offerReply"

        fun parse(raw: String?): OfficeOfferResponseCommand? {
            if (raw == null) return null
            return try {
                // The native shape is three objects deep; reject deeper input before recursive parsing.
                require(nativeDepth(raw))
                CatalogDuplicateKeys(raw).check()
                val root = Json.parseToJsonElement(raw) as? JsonObject ?: invalid()
                require(root.keys == setOf("expectedWorldId", "expectedOffer", "accepted"))
                val world = root["expectedWorldId"] as? JsonPrimitive ?: invalid()
                require(!world.isString)
                val response = root["accepted"] as? JsonPrimitive ?: invalid()
                require(!response.isString)
                val source = root["expectedOffer"] as? JsonObject ?: invalid()
                val offer = OfficeAppointmentOffer.read(mapOf(OfficeAppointmentOffer.META_KEY to nativeValue(source)))
                    ?: invalid()
                OfficeOfferResponseCommand(WorldId(integerValue(world)), offer,
                    response.booleanOrNull ?: invalid())
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun nativeDepth(raw: String): Boolean {
            var depth = 0
            var quoted = false
            var escaped = false
            for (char in raw) {
                if (quoted) {
                    if (escaped) escaped = false
                    else when (char) {
                        '\\' -> escaped = true
                        '"' -> quoted = false
                    }
                } else when (char) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; if (depth > 3) return false }
                    '}', ']' -> { depth--; if (depth < 0) return false }
                }
            }
            return depth == 0 && !quoted
        }

        private fun nativeValue(value: JsonElement): Any? = when (value) {
            JsonNull -> null
            is JsonObject -> value.mapValues { nativeValue(it.value) }
            is JsonPrimitive -> when {
                value.isString -> value.content
                value.booleanOrNull != null -> value.booleanOrNull
                else -> integerValue(value)
            }
            else -> invalid()
        }

        private fun integerValue(value: JsonPrimitive): Int {
            require(!value.isString && value.content.matches(Regex("-?(0|[1-9][0-9]*)")))
            return value.content.toIntOrNull() ?: invalid()
        }

        private fun jsonValue(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { (key, item) ->
                (key as? String ?: invalid()) to jsonValue(item)
            })
            else -> invalid()
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("invalid OFFICE reply arguments")
    }
}
