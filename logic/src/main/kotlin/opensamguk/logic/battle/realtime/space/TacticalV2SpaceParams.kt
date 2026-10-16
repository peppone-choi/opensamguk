package opensamguk.logic.battle.realtime.space

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class TacticalV2SpaceParams(val cellCapacity: Int, val soldierRadius: Fx, val sha256: String) {
    companion object {
        private const val RESOURCE = "battle/soldier/space-v1.json"

        fun load(): TacticalV2SpaceParamsLoad {
            val resource = requireNotNull(TacticalV2SpaceParams::class.java.classLoader.getResource(RESOURCE)) {
                "Space parameters resource missing"
            }
            return parse(resource.readText(Charsets.UTF_8))
        }

        /** Only a confirmed, complete decision may produce parameters for a battle. */
        fun parse(payload: String): TacticalV2SpaceParamsLoad {
            val root = Json.parseToJsonElement(payload).jsonObject
            val schema = root["schemaVersion"]?.jsonPrimitive
            require(schema != null && !schema.isString && schema.intOrNull == 1) {
                "Unsupported space parameters schema"
            }
            if (root["status"]?.jsonPrimitive?.content != "CONFIRMED") {
                return TacticalV2SpaceParamsLoad.NotDecided("NOT_DECIDED: space parameters are not CONFIRMED")
            }
            if (listOf("cellCapacity", "soldierRadiusRaw").any { root[it] == null || root[it] == JsonNull }) {
                return TacticalV2SpaceParamsLoad.NotDecided("NOT_DECIDED: space parameters are incomplete")
            }
            val capacity = root.getValue("cellCapacity").jsonPrimitive
            val radius = root.getValue("soldierRadiusRaw").jsonPrimitive
            val cellCapacity = capacity.intOrNull
            val soldierRadiusRaw = radius.longOrNull
            require(!capacity.isString && cellCapacity != null && cellCapacity > 0) {
                "cellCapacity must be a positive integer"
            }
            require(!radius.isString && soldierRadiusRaw != null && soldierRadiusRaw > 0) {
                "soldierRadiusRaw must be a positive integer"
            }
            val sha256 = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return TacticalV2SpaceParamsLoad.Ready(TacticalV2SpaceParams(cellCapacity, Fx(soldierRadiusRaw), sha256))
        }
    }
}

sealed interface TacticalV2SpaceParamsLoad {
    data class Ready(val params: TacticalV2SpaceParams) : TacticalV2SpaceParamsLoad
    data class NotDecided(val reason: String) : TacticalV2SpaceParamsLoad
}
