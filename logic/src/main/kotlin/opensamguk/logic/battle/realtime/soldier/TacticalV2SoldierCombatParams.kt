package opensamguk.logic.battle.realtime.soldier

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** New per-person values are never inferred from the v1 aggregate troop damage. */
data class TacticalV2SoldierCombatParams(val hitPoints: Int, val damage: Int, val sha256: String) {
    init {
        require(hitPoints > 0 && damage > 0)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        fun load(): TacticalV2SoldierCombatParamsLoad = parse(requireNotNull(
            TacticalV2SoldierCombatParams::class.java.classLoader.getResource(
                "battle/soldier/soldier-combat-v1.json")).readText(Charsets.UTF_8))

        fun parse(payload: String): TacticalV2SoldierCombatParamsLoad {
            val root = Json.parseToJsonElement(payload).jsonObject
            val schema = root["schemaVersion"]?.jsonPrimitive
            require(schema != null && !schema.isString && schema.intOrNull == 1)
            if (root["status"]?.jsonPrimitive?.content != "CONFIRMED" ||
                listOf("hitPoints", "damage").any { root[it] == null || root[it] == JsonNull }) {
                return TacticalV2SoldierCombatParamsLoad.NotDecided("NOT_DECIDED: soldier combat parameters")
            }
            fun positive(key: String): Int {
                val value = root.getValue(key).jsonPrimitive
                return requireNotNull(value.intOrNull).also { require(!value.isString && it > 0) }
            }
            return TacticalV2SoldierCombatParamsLoad.Ready(TacticalV2SoldierCombatParams(
                positive("hitPoints"), positive("damage"), soldierSha(payload.toByteArray(Charsets.UTF_8))))
        }
    }
}

sealed interface TacticalV2SoldierCombatParamsLoad {
    data class Ready(val params: TacticalV2SoldierCombatParams) : TacticalV2SoldierCombatParamsLoad
    data class NotDecided(val reason: String) : TacticalV2SoldierCombatParamsLoad
}

internal fun soldierSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
