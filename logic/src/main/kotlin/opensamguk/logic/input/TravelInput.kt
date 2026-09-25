package opensamguk.logic.input

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensamguk.logic.world.StrategicNodeRef

/** A direct action supplies a destination, never a caller-supplied actor, cost, path or cursor. */
data class TravelRequest(val actorId: Int, val inputId: String, val destination: StrategicNodeRef.LandProvince?)

object TravelInput {
    const val MOVE = "action.move"
    const val FORCED_MARCH = "action.forcedMarch"
    const val RETURN = "action.return"
    val INPUT_IDS = setOf(MOVE, FORCED_MARCH, RETURN)

    fun parse(actorId: Int, inputId: String, rawJson: String?): TravelRequest? {
        if (actorId <= 0 || inputId !in INPUT_IDS || rawJson == null) return null
        return try {
            val fields = FlatArguments(rawJson).read()
            if (inputId == RETURN) {
                if (fields.isNotEmpty()) return null
                TravelRequest(actorId, inputId, null)
            } else {
                if (fields.keys != setOf("destinationProvinceId")) return null
                val raw = fields["destinationProvinceId"] as? JsonPrimitive ?: return null
                if (!raw.isString || raw.content.isBlank() || raw.content.length > 128) return null
                TravelRequest(actorId, inputId, StrategicNodeRef.LandProvince(raw.content))
            }
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: TravelRequest): String {
        require(request.actorId > 0 && request.inputId in INPUT_IDS)
        return if (request.inputId == RETURN) {
            require(request.destination == null)
            "{}"
        } else {
            val destination = requireNotNull(request.destination)
            require(destination.id.isNotBlank() && destination.id.length <= 128)
            buildJsonObject { put("destinationProvinceId", destination.id) }.toString()
        }
    }
}
