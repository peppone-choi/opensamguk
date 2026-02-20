package com.opensam.command

import com.opensam.command.constraint.ConstraintResult
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import com.opensam.repository.CityRepository
import com.opensam.repository.DiplomacyRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository
import com.opensam.service.MapService
import org.springframework.stereotype.Service
import kotlin.random.Random

@Service
class CommandExecutor(
    private val commandRegistry: CommandRegistry,
    private val generalRepository: GeneralRepository,
    private val cityRepository: CityRepository,
    private val nationRepository: NationRepository,
    private val diplomacyRepository: DiplomacyRepository,
    private val mapService: MapService,
) {
    suspend fun executeGeneralCommand(
        actionCode: String,
        general: General,
        env: CommandEnv,
        arg: Map<String, Any>? = null,
        city: City? = null,
        nation: Nation? = null,
        rng: Random = Random.Default
    ): CommandResult {
        val command = commandRegistry.createGeneralCommand(actionCode, general, env, arg)
        command.city = city
        command.nation = nation
        hydrateCommandForConstraintCheck(command, general, env, arg)

        val cooldown = checkGeneralCooldown(actionCode, general, env)
        if (cooldown != null) {
            return cooldown
        }

        // Check constraints
        val conditionResult = command.checkFullCondition()
        if (conditionResult is ConstraintResult.Fail) {
            val altCode = command.getAlternativeCommand()
            if (altCode != null && altCode != actionCode) {
                return executeGeneralCommand(altCode, general, env, arg, city, nation, rng)
            }
            return CommandResult(success = false, logs = listOf(conditionResult.reason))
        }

        val preReq = command.getPreReqTurn()
        if (preReq > 0) {
            val lastTurn = LastTurn.fromMap(general.lastTurn)
            val stacked = lastTurn.addTermStack(actionCode, arg, preReq)

            if ((stacked.term ?: 0) < preReq) {
                general.lastTurn = stacked.toMap()
                return CommandResult(
                    success = true,
                    logs = listOf("${command.actionName} 수행중... (${stacked.term}/$preReq)"),
                )
            }
        }

        val result = command.run(rng)
        general.lastTurn = LastTurn(
            command = actionCode,
            arg = arg,
            term = if (preReq > 0) preReq else null,
        ).toMap()

        applyGeneralCooldown(actionCode, command.getPostReqTurn(), general, env)
        return result
    }

    suspend fun executeNationCommand(
        actionCode: String,
        general: General,
        env: CommandEnv,
        arg: Map<String, Any>? = null,
        city: City? = null,
        nation: Nation? = null,
        rng: Random = Random.Default
    ): CommandResult {
        val command = commandRegistry.createNationCommand(actionCode, general, env, arg)
            ?: return CommandResult(success = false, logs = listOf("알 수 없는 국가 명령: $actionCode"))
        command.city = city
        command.nation = nation
        hydrateCommandForConstraintCheck(command, general, env, arg)

        val cooldown = checkNationCooldown(actionCode, general, nation, env)
        if (cooldown != null) {
            return cooldown
        }

        val conditionResult = command.checkFullCondition()
        if (conditionResult is ConstraintResult.Fail) {
            return CommandResult(success = false, logs = listOf(conditionResult.reason))
        }

        val preReq = command.getPreReqTurn()
        if (preReq > 0 && nation != null) {
            val lastTurn = getNationLastTurn(nation, general.officerLevel)
            val stacked = lastTurn.addTermStack(actionCode, arg, preReq)

            if ((stacked.term ?: 0) < preReq) {
                setNationLastTurn(nation, general.officerLevel, stacked)
                return CommandResult(
                    success = true,
                    logs = listOf("${command.actionName} 수행중... (${stacked.term}/$preReq)"),
                )
            }
        }

        val result = command.run(rng)
        if (nation != null) {
            setNationLastTurn(
                nation,
                general.officerLevel,
                LastTurn(actionCode, arg, if (preReq > 0) preReq else null),
            )
        }

        applyNationCooldown(actionCode, command.getPostReqTurn(), general, nation, env)
        return result
    }

    private fun toTurnIndex(env: CommandEnv): Int {
        return env.year * 12 + env.month
    }

    private fun checkGeneralCooldown(actionCode: String, general: General, env: CommandEnv): CommandResult? {
        val nextExecuteMap = parseIntMap(general.meta[GENERAL_NEXT_EXECUTE_KEY])
        val blockedUntil = nextExecuteMap[actionCode] ?: return null
        val nowTurn = toTurnIndex(env)
        if (nowTurn < blockedUntil) {
            val remain = blockedUntil - nowTurn
            return CommandResult(
                success = false,
                logs = listOf("해당 명령은 쿨다운 중입니다. (${remain}턴 남음)"),
            )
        }
        return null
    }

    private fun applyGeneralCooldown(actionCode: String, postReqTurn: Int, general: General, env: CommandEnv) {
        if (postReqTurn <= 0) return
        val map = parseIntMap(general.meta[GENERAL_NEXT_EXECUTE_KEY])
        map[actionCode] = toTurnIndex(env) + postReqTurn
        general.meta[GENERAL_NEXT_EXECUTE_KEY] = map.mapValues { it.value as Any }.toMutableMap()
    }

    private fun checkNationCooldown(
        actionCode: String,
        general: General,
        nation: Nation?,
        env: CommandEnv,
    ): CommandResult? {
        if (nation == null) return null
        val key = nationCooldownKey(general.officerLevel)
        val nextExecuteMap = parseIntMap(nation.meta[key])
        val blockedUntil = nextExecuteMap[actionCode] ?: return null
        val nowTurn = toTurnIndex(env)
        if (nowTurn < blockedUntil) {
            val remain = blockedUntil - nowTurn
            return CommandResult(
                success = false,
                logs = listOf("해당 국가 명령은 쿨다운 중입니다. (${remain}턴 남음)"),
            )
        }
        return null
    }

    private fun applyNationCooldown(
        actionCode: String,
        postReqTurn: Int,
        general: General,
        nation: Nation?,
        env: CommandEnv,
    ) {
        if (nation == null || postReqTurn <= 0) return
        val key = nationCooldownKey(general.officerLevel)
        val map = parseIntMap(nation.meta[key])
        map[actionCode] = toTurnIndex(env) + postReqTurn
        nation.meta[key] = map.mapValues { it.value as Any }.toMutableMap()
    }

    private fun getNationLastTurn(nation: Nation, officerLevel: Short): LastTurn {
        val key = nationLastTurnKey(officerLevel)
        @Suppress("UNCHECKED_CAST")
        val raw = nation.meta[key] as? Map<String, Any>
        return LastTurn.fromMap(raw)
    }

    private fun setNationLastTurn(nation: Nation, officerLevel: Short, lastTurn: LastTurn) {
        val key = nationLastTurnKey(officerLevel)
        nation.meta[key] = lastTurn.toMap()
    }

    private fun nationLastTurnKey(officerLevel: Short): String {
        return "turn_last_$officerLevel"
    }

    private fun nationCooldownKey(officerLevel: Short): String {
        return "turn_next_$officerLevel"
    }

    private fun applyDestinationContext(command: BaseCommand, arg: Map<String, Any>?) {
        if (arg == null) return

        var destCity = extractLong(
            arg,
            "destCityId",
            "destCityID",
            "cityId",
            "targetCityId",
        )?.let { cityRepository.findById(it).orElse(null) }

        var destNation = extractLong(
            arg,
            "destNationId",
            "destNationID",
            "targetNationId",
            "nationId",
        )?.let { nationRepository.findById(it).orElse(null) }

        var destGeneral = extractLong(
            arg,
            "destGeneralID",
            "destGeneralId",
            "targetGeneralId",
            "generalId",
        )?.let { generalRepository.findById(it).orElse(null) }

        if (destCity == null && destGeneral != null) {
            destCity = cityRepository.findById(destGeneral.cityId).orElse(null)
        }
        if (destNation == null && destGeneral != null && destGeneral.nationId != 0L) {
            destNation = nationRepository.findById(destGeneral.nationId).orElse(null)
        }
        if (destNation == null && destCity != null && destCity.nationId != 0L) {
            destNation = nationRepository.findById(destCity.nationId).orElse(null)
        }

        if (destGeneral == null && destNation != null) {
            if (destNation.chiefGeneralId > 0L) {
                destGeneral = generalRepository.findById(destNation.chiefGeneralId).orElse(null)
            }
            if (destGeneral == null) {
                destGeneral = generalRepository.findByNationId(destNation.id)
                    .maxByOrNull { it.officerLevel }
            }
        }

        command.destCity = destCity
        command.destNation = destNation
        command.destGeneral = destGeneral
    }

    fun hydrateCommandForConstraintCheck(
        command: BaseCommand,
        general: General,
        env: CommandEnv,
        arg: Map<String, Any>?,
    ) {
        applyDestinationContext(command, arg)
        command.constraintEnv = buildConstraintEnv(general, env)
    }

    private fun buildConstraintEnv(general: General, env: CommandEnv): Map<String, Any> {
        val worldId = env.worldId
        val mapName = (env.gameStor["mapName"] as? String) ?: "che"

        val allCities = cityRepository.findByWorldId(worldId)
        val cityNationById = allCities.associate { it.id to it.nationId }
        val citySupplyStateById = allCities.associate { it.id to it.supplyState.toInt() }

        val allGenerals = generalRepository.findByWorldId(worldId)
        val totalNpcCount = allGenerals.count { it.npcState.toInt() > 0 }
        val totalGeneralCount = allGenerals.size - totalNpcCount

        val mapAdjacency = try {
            mapService.getCities(mapName).associate { cityConst ->
                cityConst.id.toLong() to cityConst.connections.map { it.toLong() }
            }
        } catch (_: Exception) {
            emptyMap()
        }

        val troopMemberExistsByTroopId = generalRepository.findByWorldId(worldId)
            .asSequence()
            .filter { it.troopId > 0L }
            .groupBy { it.troopId }
            .mapValues { (_, members) -> members.any { m -> m.id != m.troopId } }

        val atWarNationIds = if (general.nationId == 0L) {
            emptySet()
        } else {
            diplomacyRepository.findByWorldIdAndIsDeadFalse(worldId)
                .asSequence()
                .filter { it.stateCode == "선전포고" }
                .mapNotNull {
                    when (general.nationId) {
                        it.srcNationId -> it.destNationId
                        it.destNationId -> it.srcNationId
                        else -> null
                    }
                }
                .toSet()
        }

        return mapOf(
            "worldId" to worldId,
            "mapName" to mapName,
            "mapAdjacency" to mapAdjacency,
            "cityNationById" to cityNationById,
            "citySupplyStateById" to citySupplyStateById,
            "totalGeneralCount" to totalGeneralCount,
            "totalNpcCount" to totalNpcCount,
            "troopMemberExistsByTroopId" to troopMemberExistsByTroopId,
            "atWarNationIds" to atWarNationIds,
            "joinActionLimit" to 12,
        )
    }

    private fun extractLong(arg: Map<String, Any>, vararg keys: String): Long? {
        for (key in keys) {
            val raw = arg[key] ?: continue
            val parsed = when (raw) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull()
                else -> null
            }
            if (parsed != null) return parsed
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseIntMap(raw: Any?): MutableMap<String, Int> {
        if (raw !is Map<*, *>) return mutableMapOf()
        val result = mutableMapOf<String, Int>()
        raw.forEach { (k, v) ->
            if (k is String) {
                val num = (v as? Number)?.toInt()
                if (num != null) {
                    result[k] = num
                }
            }
        }
        return result
    }

    companion object {
        private const val GENERAL_NEXT_EXECUTE_KEY = "next_execute"
    }
}
