package opensamguk.logic.input

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class HwihaTrainingStat(val wireName: String) {
    LEADERSHIP("leadership"), STRENGTH("strength"), INTELLIGENCE("intelligence"),
    POLITICS("politics"), CHARM("charm");

    companion object { fun fromWire(value: String) = entries.singleOrNull { it.wireName == value } }
}

data class HwihaPersonalRequest(val actorId: Int, val inputId: String,
    val trainingStat: HwihaTrainingStat? = null)

/** Field-phase personal inputs. Retire has a separate political-phase contract. */
object HwihaPersonalInput {
    const val TRAVEL = "action.travel"
    const val SELF_TRAIN = "action.selfTrain"
    const val RECUPERATE = "action.recuperate"
    val FIELD_IDS = linkedSetOf(TRAVEL, SELF_TRAIN, RECUPERATE)

    fun parse(actorId: Int, inputId: String, rawJson: String?): HwihaPersonalRequest? {
        if (actorId <= 0 || inputId !in FIELD_IDS || rawJson == null) return null
        return try {
            val fields = HwihaFlatArguments(rawJson).read()
            if (inputId == SELF_TRAIN) {
                if (fields.keys != setOf("stat")) return null
                val raw = fields["stat"] as? JsonPrimitive ?: return null
                if (!raw.isString) return null
                val stat = HwihaTrainingStat.fromWire(raw.content) ?: return null
                HwihaPersonalRequest(actorId, inputId, stat)
            } else if (fields.isEmpty()) HwihaPersonalRequest(actorId, inputId) else null
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: HwihaPersonalRequest): String {
        require(request.actorId > 0 && request.inputId in FIELD_IDS)
        return if (request.inputId == SELF_TRAIN) {
            val stat = requireNotNull(request.trainingStat)
            buildJsonObject { put("stat", stat.wireName) }.toString()
        } else {
            require(request.trainingStat == null)
            "{}"
        }
    }
}
