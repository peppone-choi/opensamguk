package opensamguk.logic.input

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

sealed interface HwihaCourtExpansionRequest { val actorId: Int
    data class County(override val actorId: Int, val inputId: String, val countyId: Int) : HwihaCourtExpansionRequest
    data class ReleaseCorps(override val actorId: Int, val targetGeneralId: Int) : HwihaCourtExpansionRequest
}

/** County and corps identifiers are requests; mandate, ownership and current state are server decisions. */
object HwihaCourtExpansionInput {
    const val RELEASE_CORPS = "court.releaseCorps"
    const val ABANDON_COUNTY = "court.abandonCounty"
    const val MOVE_CAPITAL = "court.moveCapital"
    val INPUT_IDS = linkedSetOf(RELEASE_CORPS, ABANDON_COUNTY, MOVE_CAPITAL)

    fun parse(actorId: Int, inputId: String, rawJson: String?): HwihaCourtExpansionRequest? {
        if (actorId <= 0 || inputId !in INPUT_IDS || rawJson == null) return null
        return try {
            val fields = HwihaFlatArguments(rawJson).read()
            if (inputId == RELEASE_CORPS) {
                if (fields.keys != setOf("targetGeneralId")) return null
                val target = fields["targetGeneralId"] as? JsonPrimitive ?: return null
                val id = target.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 && it != actorId } ?: return null
                HwihaCourtExpansionRequest.ReleaseCorps(actorId, id)
            } else {
                if (fields.keys != setOf("countyId")) return null
                val raw = fields["countyId"] as? JsonPrimitive ?: return null
                val id = raw.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 } ?: return null
                HwihaCourtExpansionRequest.County(actorId, inputId, id)
            }
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: HwihaCourtExpansionRequest): String {
        require(request.actorId > 0)
        return when (request) {
            is HwihaCourtExpansionRequest.ReleaseCorps -> buildJsonObject {
                require(request.targetGeneralId > 0 && request.targetGeneralId != request.actorId)
                put("targetGeneralId", request.targetGeneralId)
            }.toString()
            is HwihaCourtExpansionRequest.County -> buildJsonObject {
                require(request.inputId == ABANDON_COUNTY || request.inputId == MOVE_CAPITAL)
                require(request.countyId > 0)
                put("countyId", request.countyId)
            }.toString()
        }
    }
}
