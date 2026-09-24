package opensamguk.logic.input

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class HwihaPoliticalRequest(val actorId: Int, val inputId: String, val targetGeneralId: Int? = null)

/** Strict argument contracts for the political actions whose target shape is fixed by §5.1. */
object HwihaPoliticalInput {
    const val RESIGN = "action.resign"
    const val RISE = "action.rise"
    const val FOUND_STATE = "action.foundState"
    const val ABDICATE = "action.abdicate"
    const val DISSOLVE = "action.dissolve"
    const val OATH = "action.oath"
    const val INDEPENDENCE = "action.independence"

    val NO_ARGUMENT_IDS = linkedSetOf(RESIGN, RISE, FOUND_STATE, DISSOLVE, INDEPENDENCE)
    val TARGET_IDS = linkedSetOf(ABDICATE, OATH)
    val INPUT_IDS = NO_ARGUMENT_IDS + TARGET_IDS

    fun parse(actorId: Int, inputId: String, rawJson: String?): HwihaPoliticalRequest? {
        if (actorId <= 0 || inputId !in INPUT_IDS || rawJson == null) return null
        return try {
            val fields = HwihaFlatArguments(rawJson).read()
            if (inputId in NO_ARGUMENT_IDS) {
                if (fields.isEmpty()) HwihaPoliticalRequest(actorId, inputId) else null
            } else {
                if (fields.keys != setOf("targetGeneralId")) return null
                val target = fields["targetGeneralId"] as? JsonPrimitive ?: return null
                val id = if (!target.isString) target.intOrNull else null
                if (id == null || id <= 0 || id == actorId) null else HwihaPoliticalRequest(actorId, inputId, id)
            }
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: HwihaPoliticalRequest): String {
        require(request.actorId > 0 && request.inputId in INPUT_IDS)
        return if (request.inputId in NO_ARGUMENT_IDS) {
            require(request.targetGeneralId == null)
            "{}"
        } else {
            val id = requireNotNull(request.targetGeneralId)
            require(id > 0 && id != request.actorId)
            buildJsonObject { put("targetGeneralId", id) }.toString()
        }
    }
}
