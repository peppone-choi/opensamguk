package opensamguk.logic.input

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

enum class TradeSide { BUY, SELL }
enum class Cargo { MONEY, GRAIN, IRON, TIMBER, HORSES }

sealed interface DirectRequest { val actorId: Int
    data class Convert(override val actorId: Int, val bugokId: Int, val crewTypeId: Int) : DirectRequest
    data class Equipment(override val actorId: Int, val treasureId: Int, val side: TradeSide) : DirectRequest
    data class Grain(override val actorId: Int, val side: TradeSide, val amount: Int) : DirectRequest
    data class Transport(override val actorId: Int, val targetCountyId: Int, val cargo: Cargo,
        val amount: Int) : DirectRequest
}

/** Server-owned actor, location, prices and stocks never come from the client JSON. */
object DirectInput {
    const val CONVERT = "action.convertProficiency"
    const val EQUIPMENT = "action.tradeEquipment"
    const val GRAIN = "action.tradeGrain"
    const val TRANSPORT = "action.transport"
    val INPUT_IDS = linkedSetOf(CONVERT, EQUIPMENT, GRAIN, TRANSPORT)

    fun parse(actorId: Int, inputId: String, rawJson: String?): DirectRequest? {
        if (actorId <= 0 || inputId !in INPUT_IDS || rawJson == null) return null
        return try {
            val fields = FlatArguments(rawJson).read()
            fun positive(key: String): Int? = (fields[key] as? JsonPrimitive)
                ?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 }
            fun side(): TradeSide? = (fields["side"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.let { raw -> TradeSide.entries.singleOrNull { it.name == raw } }
            when (inputId) {
                CONVERT -> if (fields.keys == setOf("bugokId", "crewTypeId")) {
                    val bugokId = positive("bugokId") ?: return null
                    val crewTypeId = positive("crewTypeId") ?: return null
                    DirectRequest.Convert(actorId, bugokId, crewTypeId)
                } else null
                EQUIPMENT -> if (fields.keys == setOf("treasureId", "side")) {
                    DirectRequest.Equipment(actorId, positive("treasureId") ?: return null,
                        side() ?: return null)
                } else null
                GRAIN -> if (fields.keys == setOf("side", "amount")) {
                    DirectRequest.Grain(actorId, side() ?: return null, positive("amount") ?: return null)
                } else null
                TRANSPORT -> if (fields.keys == setOf("targetCountyId", "cargo", "amount")) {
                    val cargo = (fields["cargo"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?.let { raw -> Cargo.entries.singleOrNull { it.name == raw } } ?: return null
                    DirectRequest.Transport(actorId, positive("targetCountyId") ?: return null,
                        cargo, positive("amount") ?: return null)
                } else null
                else -> null
            }
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: DirectRequest): String {
        require(request.actorId > 0)
        return when (request) {
            is DirectRequest.Convert -> buildJsonObject {
                require(request.bugokId > 0 && request.crewTypeId > 0)
                put("bugokId", request.bugokId); put("crewTypeId", request.crewTypeId)
            }.toString()
            is DirectRequest.Equipment -> buildJsonObject {
                require(request.treasureId > 0)
                put("treasureId", request.treasureId); put("side", request.side.name)
            }.toString()
            is DirectRequest.Grain -> buildJsonObject {
                require(request.amount > 0)
                put("side", request.side.name); put("amount", request.amount)
            }.toString()
            is DirectRequest.Transport -> buildJsonObject {
                require(request.targetCountyId > 0 && request.amount > 0)
                put("targetCountyId", request.targetCountyId); put("cargo", request.cargo.name)
                put("amount", request.amount)
            }.toString()
        }
    }
}
