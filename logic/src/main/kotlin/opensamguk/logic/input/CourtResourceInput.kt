package opensamguk.logic.input

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class CourtResourceRequest(val actorId: Int, val inputId: String, val targetId: Int,
    val resource: TransferResource, val amount: Int)

/** Confiscation targets a person; material aid targets another nation. */
object CourtResourceInput {
    const val CONFISCATE = "court.confiscate"
    const val AID = "court.diplomacy"
    val INPUT_IDS = linkedSetOf(CONFISCATE, AID)

    fun parse(actorId: Int, inputId: String, rawJson: String?): CourtResourceRequest? {
        if (actorId <= 0 || inputId !in INPUT_IDS || rawJson == null) return null
        return try {
            val fields = FlatArguments(rawJson).read()
            val targetKey = if (inputId == CONFISCATE) "targetGeneralId" else "targetNationId"
            if (fields.keys != setOf(targetKey, "resource", "amount")) return null
            fun positive(key: String): Int? = (fields[key] as? JsonPrimitive)
                ?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 }
            val target = positive(targetKey) ?: return null
            if (inputId == CONFISCATE && target == actorId) return null
            val rawResource = fields["resource"] as? JsonPrimitive ?: return null
            val resource = rawResource.takeIf { it.isString }?.content
                ?.let { raw -> TransferResource.entries.singleOrNull { it.name == raw } } ?: return null
            CourtResourceRequest(actorId, inputId, target, resource, positive("amount") ?: return null)
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: CourtResourceRequest): String {
        require(request.actorId > 0 && request.inputId in INPUT_IDS && request.targetId > 0 && request.amount > 0)
        require(request.inputId != CONFISCATE || request.targetId != request.actorId)
        return buildJsonObject {
            put(if (request.inputId == CONFISCATE) "targetGeneralId" else "targetNationId", request.targetId)
            put("resource", request.resource.name)
            put("amount", request.amount)
        }.toString()
    }
}
