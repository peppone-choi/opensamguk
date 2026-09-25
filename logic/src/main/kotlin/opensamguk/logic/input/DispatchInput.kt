package opensamguk.logic.input

import kotlinx.serialization.json.*

data class DispatchRequest(val actorId: Int, val targetGeneralId: Int, val countyId: Int)
data class DispatchReplyRequest(val actorId: Int, val dispatchId: String, val accept: Boolean)

private fun positiveId(value: JsonElement?): Int? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString || !Regex("[1-9][0-9]*").matches(primitive.content)) return null
    return primitive.content.toIntOrNull()
}

/** Actor identity is supplied by the authenticated caller, never by the JSON body. */
object DispatchInput {
    fun parse(actorId: Int, rawJson: String?): DispatchRequest? {
        if (actorId <= 0 || rawJson == null) return null
        return try {
            val fields = FlatArguments(rawJson).read()
            if (fields.keys != setOf("targetGeneralId", "countyId")) return null
            DispatchRequest(actorId, positiveId(fields["targetGeneralId"]) ?: return null,
                positiveId(fields["countyId"]) ?: return null)
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: DispatchRequest): String {
        require(request.actorId > 0 && request.targetGeneralId > 0 && request.countyId > 0)
        return buildJsonObject {
            put("targetGeneralId", request.targetGeneralId)
            put("countyId", request.countyId)
        }.toString()
    }
}

object DispatchReplyInput {
    private val idPattern = Regex("[A-Za-z0-9._:-]{1,128}")

    fun parse(actorId: Int, rawJson: String?): DispatchReplyRequest? {
        if (actorId <= 0 || rawJson == null) return null
        return try {
            val fields = FlatArguments(rawJson).read()
            if (fields.keys != setOf("dispatchId", "accept")) return null
            val id = fields["dispatchId"] as? JsonPrimitive ?: return null
            if (!id.isString || !idPattern.matches(id.content)) return null
            val accept = fields["accept"] as? JsonPrimitive ?: return null
            if (accept.isString) return null
            val answer = when (accept.content) { "true" -> true; "false" -> false; else -> return null }
            DispatchReplyRequest(actorId, id.content, answer)
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: DispatchReplyRequest): String {
        require(request.actorId > 0 && idPattern.matches(request.dispatchId))
        return buildJsonObject { put("dispatchId", request.dispatchId); put("accept", request.accept) }.toString()
    }
}
