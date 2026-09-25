package opensamguk.logic.input

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The actor and current location come from the server; only target identity may be supplied. */
data class HwihaPeopleRequest(val actorId: Int, val inputId: String, val targetGeneralId: Int?)

object HwihaPeopleInput {
    const val SEARCH = "action.search"
    const val EMPLOY = "action.employ"
    const val PERSUADE_CAPTIVE = "action.persuadeCaptive"
    val INPUT_IDS = linkedSetOf(SEARCH, EMPLOY, PERSUADE_CAPTIVE)

    fun parse(actorId: Int, inputId: String, rawJson: String?): HwihaPeopleRequest? {
        if (actorId <= 0 || inputId !in INPUT_IDS || rawJson == null) return null
        return try {
            val fields = HwihaFlatArguments(rawJson).read()
            if (inputId == SEARCH) {
                if (fields.isNotEmpty()) null else HwihaPeopleRequest(actorId, inputId, null)
            } else {
                if (fields.keys != setOf("targetGeneralId")) return null
                val target = fields["targetGeneralId"] as? JsonPrimitive ?: return null
                val id = if (!target.isString) target.intOrNull else null
                if (id == null || id <= 0 || id == actorId) null else HwihaPeopleRequest(actorId, inputId, id)
            }
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: HwihaPeopleRequest): String {
        require(request.actorId > 0 && request.inputId in INPUT_IDS)
        return if (request.inputId == SEARCH) {
            require(request.targetGeneralId == null)
            "{}"
        } else {
            val id = requireNotNull(request.targetGeneralId)
            require(id > 0 && id != request.actorId)
            buildJsonObject { put("targetGeneralId", id) }.toString()
        }
    }
}
