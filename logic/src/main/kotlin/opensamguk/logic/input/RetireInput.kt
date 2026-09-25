package opensamguk.logic.input

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class HwihaRetireRequest(val actorId: Int, val successorGeneralId: Int)

/** A retiring general names the successor; the server resolves current retainer ownership. */
object HwihaRetireInput {
    const val INPUT_ID = "action.retire"

    fun parse(actorId: Int, rawJson: String?): HwihaRetireRequest? {
        if (actorId <= 0 || rawJson == null) return null
        return try {
            val fields = HwihaFlatArguments(rawJson).read()
            if (fields.keys != setOf("successorGeneralId")) return null
            val raw = fields["successorGeneralId"] as? JsonPrimitive ?: return null
            val id = if (raw.isString) null else raw.intOrNull
            if (id == null || id <= 0 || id == actorId) null else HwihaRetireRequest(actorId, id)
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: HwihaRetireRequest): String {
        require(request.actorId > 0 && request.successorGeneralId > 0 && request.successorGeneralId != request.actorId)
        return buildJsonObject { put("successorGeneralId", request.successorGeneralId) }.toString()
    }
}
