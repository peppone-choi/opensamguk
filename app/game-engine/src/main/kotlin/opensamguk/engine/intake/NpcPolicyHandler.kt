package opensamguk.engine.intake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import opensamguk.common.wire.NationSettingResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.Troop
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.actions.intake.SecretPermission
import opensamguk.logic.ai.AutorunGeneralPolicy
import opensamguk.logic.ai.AutorunNationPolicy
import java.time.Clock
import java.time.Instant

class NpcPolicyHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: TurnDaemonCommand.NpcPolicyUpdate): NationSettingResult {
        val me = world.getGeneralById(command.generalId)
            ?: return denied(command.generalId, null, "권한이 부족합니다. 군주, 외교권자, 조언자가 아닙니다.")
        val nation = world.getNationById(me.nationId)
            ?: return denied(command.generalId, null, "국가에 소속되어있지 않습니다.")
        if (secretPermission(me, nation) < 3) {
            return denied(command.generalId, nation.id, "권한이 부족합니다. 군주, 외교권자, 조언자가 아닙니다.")
        }

        val reason = when (command.policyType) {
            "nationPolicy" -> applyNationPolicy(command.data, nation, me.name)
            "nationPriority" -> applyNationPriority(command.data, nation, me.name)
            "generalPriority" -> applyGeneralPriority(command.data, nation, me.name)
            else -> "올바른 타입이 아닙니다."
        }
        return if (reason == null) {
            NationSettingResult(type = "npcPolicyUpdate", ok = true, generalId = command.generalId, nationId = nation.id)
        } else {
            denied(command.generalId, nation.id, reason)
        }
    }

    private fun applyNationPolicy(data: JsonElement, nation: Nation, setter: String): String? {
        val policy = data as? JsonObject ?: return "올바른 입력이 아닙니다."
        val root = nationEnvRoot(nation, "npc_nation_policy")
        val values = linkedMapOfStringAny(root["values"])
        val troops = world.listTroops().filter { it.nationId == nation.id }.associateBy { it.id }
        val troopRoles = LinkedHashMap<Int, String>()
        troops.keys.forEach { troopRoles[it] = "Neutral" }

        for ((key, rawValue) in policy) {
            when (key) {
                "CombatForce" -> {
                    val force = rawValue as? JsonObject ?: return "${key}는 올바른 정책값이 아닙니다."
                    for ((troopIdText, target) in force) {
                        val troopId = troopIdText.toIntOrNull() ?: return "${troopIdText}는 국가의 부대가 아닙니다."
                        validateTroopRole(troopId, troops, troopRoles)?.let { return it }
                        val cities = target as? JsonArray ?: return "${troopId}의 입력양식이 올바르지 않습니다."
                        if (cities.size != 2 || cities.any { (it as? JsonPrimitive)?.intOrNull == null }) {
                            return "${troopId}의 입력양식이 올바르지 않습니다."
                        }
                        troopRoles[troopId] = key
                    }
                    values[key] = jsonToAny(rawValue)
                }
                "SupportForce", "DevelopForce" -> {
                    val force = rawValue as? JsonArray ?: return "${key}는 올바른 정책값이 아닙니다."
                    val ids = mutableListOf<Int>()
                    for (item in force) {
                        val troopId = (item as? JsonPrimitive)?.intOrNull
                            ?: return "${key}는 올바른 정책값이 아닙니다."
                        validateTroopRole(troopId, troops, troopRoles)?.let { return it }
                        troopRoles[troopId] = key
                        ids += troopId
                    }
                    values[key] = ids
                }
                else -> {
                    val defaultValue = AutorunNationPolicy.DEFAULT_POLICY[key]
                        ?: return "${key}는 올바른 정책값이 아닙니다."
                    val nextValue = validatePolicyValue(key, defaultValue, rawValue) ?: return "${key}는 올바른 값이 아닙니다."
                    values[key] = nextValue
                }
            }
        }

        root["values"] = values
        root["valueSetter"] = setter
        root["valueSetTime"] = now()
        writeNationEnv(nation.id, "npc_nation_policy", root)
        return null
    }

    private fun applyNationPriority(data: JsonElement, nation: Nation, setter: String): String? {
        val priority = stringList(data) ?: return "올바른 입력이 아닙니다."
        for (item in priority) {
            if (item !in AutorunNationPolicy.DEFAULT_PRIORITY) {
                return "${item}은 올바른 명령이 아닙니다."
            }
        }
        val root = nationEnvRoot(nation, "npc_nation_policy")
        root["priority"] = priority
        root["prioritySetter"] = setter
        root["prioritySetTime"] = now()
        writeNationEnv(nation.id, "npc_nation_policy", root)
        return null
    }

    private fun applyGeneralPriority(data: JsonElement, nation: Nation, setter: String): String? {
        val priority = stringList(data) ?: return "올바른 입력이 아닙니다."
        val order = LinkedHashMap<String, Int>()
        val mustHave = linkedMapOf("출병" to true, "일반내정" to true)
        for (item in priority) {
            if (item in mustHave) mustHave[item] = false
            if (item !in AutorunGeneralPolicy.DEFAULT_PRIORITY) {
                return "${item}은 올바른 명령이 아닙니다."
            }
            order[item] = order.size
        }
        val sortieOrder = order["출병"]
        val domesticOrder = order["일반내정"]
        if (sortieOrder != null && domesticOrder != null && sortieOrder > domesticOrder) {
            return "출병 명령은 일반내정 명령보다 먼저여야 합니다."
        }
        for ((action, missing) in mustHave) {
            if (missing) return "${action}은 항상 사용해야 합니다."
        }

        val root = nationEnvRoot(nation, "npc_general_policy")
        root["priority"] = priority
        root["prioritySetter"] = setter
        root["prioritySetTime"] = now()
        writeNationEnv(nation.id, "npc_general_policy", root)
        return null
    }

    private fun validateTroopRole(
        troopId: Int,
        troops: Map<Int, Troop>,
        troopRoles: MutableMap<Int, String>,
    ): String? {
        if (troopId !in troops) return "${troopId}는 국가의 부대가 아닙니다."
        if (troopRoles[troopId] != "Neutral") return "부대(${troopId})는 하나의 역할만 지정할 수 있습니다."
        return null
    }

    private fun validatePolicyValue(key: String, defaultValue: Any?, value: JsonElement): Any? {
        return when (defaultValue) {
            is Int -> {
                val intValue = (value as? JsonPrimitive)?.intOrNull ?: return null
                intValue.coerceAtLeast(0)
            }
            is Double -> (value as? JsonPrimitive)?.doubleOrNull
            is List<*> -> if (value is JsonArray) jsonToAny(value) else null
            is Map<*, *> -> if (value is JsonObject) jsonToAny(value) else null
            else -> if (key.isNotBlank()) jsonToAny(value) else null
        }
    }

    private fun secretPermission(me: TurnGeneral, nation: Nation): Int =
        SecretPermission.check(
            nationId = me.nationId,
            officerLevel = me.officerLevel,
            meta = me.meta,
            penalty = (me.meta["penalty"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap(),
            checkSecretLimit = true,
        ) { (nation.meta["secretlimit"] as? Number)?.toInt() ?: 3 }

    private fun nationEnvRoot(nation: Nation, key: String): LinkedHashMap<String, Any?> =
        linkedMapOfStringAny(nationEnv(nation)[key])

    private fun nationEnv(nation: Nation): Map<String, Any?> =
        (nation.meta["nation_env"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()

    private fun writeNationEnv(nationId: Int, key: String, value: Map<String, Any?>) {
        recorder.recordNationEnvKv(nationId, key, value)
        world.applyKvDirtyFree(KvKey("nation_env", nationId.toString(), key), value)
    }

    private fun now(): String = Instant.now(clock).toString()

    private fun denied(generalId: Int, nationId: Int?, reason: String) =
        NationSettingResult(type = "npcPolicyUpdate", ok = false, generalId = generalId, nationId = nationId, reason = reason)
}

private fun linkedMapOfStringAny(value: Any?): LinkedHashMap<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    (value as? Map<*, *>)?.forEach { (k, v) -> out[k.toString()] = v }
    return out
}

private fun stringList(value: JsonElement): List<String>? =
    (value as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: return null }

private fun jsonToAny(value: JsonElement): Any? = when (value) {
    JsonNull -> null
    is JsonObject -> value.entries.associateTo(LinkedHashMap()) { (k, v) -> k to jsonToAny(v) }
    is JsonArray -> value.map { jsonToAny(it) }
    is JsonPrimitive -> when {
        value.booleanOrNull != null -> value.boolean
        value.intOrNull != null -> value.intOrNull
        value.doubleOrNull != null -> value.doubleOrNull
        else -> value.content
    }
}
