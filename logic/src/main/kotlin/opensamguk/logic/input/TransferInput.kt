package opensamguk.logic.input

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

enum class HwihaTransferResource { MONEY, GRAIN, IRON, TIMBER, HORSES }
data class HwihaTransferRequest(val actorId: Int, val inputId: String, val resource: HwihaTransferResource,
    val amount: Int, val targetGeneralId: Int? = null)

/** The recipient's location and the stock are resolved from the current server state. */
object HwihaTransferInput {
    const val GIFT = "action.gift"
    const val DONATE = "action.donate"
    val INPUT_IDS = linkedSetOf(GIFT, DONATE)

    fun parse(actorId: Int, inputId: String, rawJson: String?): HwihaTransferRequest? {
        if (actorId <= 0 || inputId !in INPUT_IDS || rawJson == null) return null
        return try {
            val fields = HwihaFlatArguments(rawJson).read()
            val expected = if (inputId == GIFT) setOf("targetGeneralId", "resource", "amount")
                else setOf("resource", "amount")
            if (fields.keys != expected) return null
            val rawResource = fields["resource"] as? JsonPrimitive ?: return null
            if (!rawResource.isString) return null
            val resource = HwihaTransferResource.entries.singleOrNull { it.name == rawResource.content } ?: return null
            fun positive(key: String): Int? = (fields[key] as? JsonPrimitive)
                ?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 }
            val amount = positive("amount") ?: return null
            val target = if (inputId == GIFT) positive("targetGeneralId") ?: return null else null
            if (target == actorId) return null
            HwihaTransferRequest(actorId, inputId, resource, amount, target)
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: HwihaTransferRequest): String {
        require(request.actorId > 0 && request.inputId in INPUT_IDS && request.amount > 0)
        return buildJsonObject {
            if (request.inputId == GIFT) {
                val target = requireNotNull(request.targetGeneralId)
                require(target > 0 && target != request.actorId)
                put("targetGeneralId", target)
            } else require(request.targetGeneralId == null)
            put("resource", request.resource.name)
            put("amount", request.amount)
        }.toString()
    }
}
