package opensamguk.logic.input

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class DiplomacyRequest(val actorId: Int, val inputId: String, val targetNationId: Int)

/** Four legacy diplomatic proposals share the envoy and target-nation gate at execution. */
object DiplomacyInput {
    const val NON_AGGRESSION = "court.nonAggression"
    const val DECLARE_WAR = "court.declareWar"
    const val OFFER_PEACE = "court.offerPeace"
    const val BREAK_NON_AGGRESSION = "court.breakNonAggression"
    val INPUT_IDS = linkedSetOf(NON_AGGRESSION, DECLARE_WAR, OFFER_PEACE, BREAK_NON_AGGRESSION)

    fun parse(actorId: Int, inputId: String, rawJson: String?): DiplomacyRequest? {
        if (actorId <= 0 || inputId !in INPUT_IDS || rawJson == null) return null
        return try {
            val fields = FlatArguments(rawJson).read()
            if (fields.keys != setOf("targetNationId")) return null
            val target = fields["targetNationId"] as? JsonPrimitive ?: return null
            val id = target.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 } ?: return null
            DiplomacyRequest(actorId, inputId, id)
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: DiplomacyRequest): String {
        require(request.actorId > 0 && request.inputId in INPUT_IDS && request.targetNationId > 0)
        return buildJsonObject { put("targetNationId", request.targetNationId) }.toString()
    }
}
