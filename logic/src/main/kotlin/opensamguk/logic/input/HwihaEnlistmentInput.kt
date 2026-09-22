package opensamguk.logic.input

import kotlinx.serialization.json.*

/** Flat enlistment arguments only. Actor identity always comes from the authenticated caller. */
object HwihaEnlistmentInput {
    fun parse(actorId: Int, rawJson: String?): EnlistmentRequest? {
        if (actorId <= 0 || rawJson == null) return null
        return try {
            val fields = HwihaFlatArguments(rawJson).read()
            val modeValue = fields["mode"] as? JsonPrimitive ?: return null
            if (!modeValue.isString) return null
            val mode = EnlistmentMode.entries.firstOrNull { it.name == modeValue.content } ?: return null
            if (mode == EnlistmentMode.RANDOM) {
                if (fields.keys != setOf("mode")) return null
                EnlistmentRequest(actorId, mode)
            } else {
                if (fields.keys != setOf("mode", "targetId")) return null
                val target = fields["targetId"] as? JsonPrimitive ?: return null
                if (target.isString || !Regex("[1-9][0-9]*").matches(target.content)) return null
                val id = target.content.toIntOrNull() ?: return null
                EnlistmentRequest(actorId, mode, id)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun canonicalJson(request: EnlistmentRequest): String {
        require(request.actorId > 0)
        require(if (request.mode == EnlistmentMode.RANDOM) request.targetId == null else (request.targetId ?: 0) > 0)
        return buildJsonObject {
            put("mode", request.mode.name)
            request.targetId?.let { put("targetId", it) }
        }.toString()
    }

}
