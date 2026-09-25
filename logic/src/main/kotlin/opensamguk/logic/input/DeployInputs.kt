package opensamguk.logic.input

import kotlinx.serialization.json.*
import opensamguk.logic.world.StrategicNodeRef

/** A personal deployment never accepts a deputy or a caller-supplied owner/order identifier. */
data class DeployInput(val actorId: Int, val bugokIds: List<Int>, val destination: StrategicNodeRef.LandProvince) {
    fun deploymentRequest() = DeploymentRequest(actorId, null, bugokIds)
}

object DeployInputs {
    const val INPUT_ID = "action.deploy"
    fun parse(actorId: Int, rawJson: String?): DeployInput? {
        if (actorId <= 0 || rawJson == null) return null
        return try {
            val fields = FlatArguments(rawJson).read(allowIntegerArrays = true)
            if (fields.keys != setOf("bugokIds", "destinationProvinceId")) return null
            val destination = fields["destinationProvinceId"] as? JsonPrimitive ?: return null
            if (!destination.isString || destination.content.isBlank() || destination.content.length > 128) return null
            val values = fields["bugokIds"] as? JsonArray ?: return null
            if (values.isEmpty()) return null
            val ids = values.map {
                val value = it as? JsonPrimitive ?: return null
                if (value.isString || !Regex("[1-9][0-9]*").matches(value.content)) return null
                value.content.toIntOrNull() ?: return null
            }
            if (ids.distinct().size != ids.size) return null
            DeployInput(actorId, ids.sorted(), StrategicNodeRef.LandProvince(destination.content))
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: DeployInput): String {
        require(request.actorId > 0 && request.bugokIds.isNotEmpty() && request.bugokIds.all { it > 0 })
        require(request.bugokIds.distinct().size == request.bugokIds.size)
        require(request.destination.id.isNotBlank() && request.destination.id.length <= 128)
        return buildJsonObject {
            putJsonArray("bugokIds") { request.bugokIds.sorted().forEach { add(it) } }
            put("destinationProvinceId", request.destination.id)
        }.toString()
    }
}
