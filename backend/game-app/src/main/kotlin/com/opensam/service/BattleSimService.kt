package com.opensam.service

import com.opensam.dto.SimulateRequest
import com.opensam.dto.SimulateResult
import com.opensam.engine.war.BattleEngine
import com.opensam.engine.war.WarUnitGeneral
import com.opensam.entity.City
import com.opensam.entity.General
import org.springframework.stereotype.Service
import kotlin.random.Random

@Service
class BattleSimService {
    private val battleEngine = BattleEngine()

    private val terrainModifier = mapOf(
        "plain" to (1.0 to 1.0),
        "forest" to (0.92 to 1.12),
        "hill" to (0.95 to 1.08),
        "mountain" to (0.85 to 1.2),
        "river" to (0.88 to 1.15),
    )

    private val weatherModifier = mapOf(
        "clear" to (1.0 to 1.0),
        "rain" to (0.9 to 1.08),
        "snow" to (0.86 to 1.12),
        "storm" to (0.82 to 1.18),
    )

    fun simulate(request: SimulateRequest): SimulateResult {
        val defenders = if (request.defenders.isEmpty()) listOf(request.defender) else request.defenders

        val city = City(
            id = 0,
            worldId = request.defenderCity.worldId,
            name = request.defenderCity.name,
            level = request.defenderCity.level.toShort(),
            nationId = request.defenderCity.nationId,
            def = request.defenderCity.def,
            defMax = request.defenderCity.def,
            wall = request.defenderCity.wall,
            wallMax = request.defenderCity.wall,
            pop = 50000,
            popMax = 50000,
        )

        val attackerGeneral = toGeneral(request.attacker)
        val attackerUnit = WarUnitGeneral(attackerGeneral)

        val defenderUnits = defenders.map { unitInfo -> WarUnitGeneral(toGeneral(unitInfo)) }

        val terrainKey = request.terrain.lowercase()
        val weatherKey = request.weather.lowercase()
        val (terrainAtk, terrainDef) = terrainModifier[terrainKey] ?: (1.0 to 1.0)
        val (weatherAtk, weatherDef) = weatherModifier[weatherKey] ?: (1.0 to 1.0)

        attackerUnit.attackMultiplier *= (terrainAtk * weatherAtk)
        attackerUnit.defenceMultiplier *= (terrainDef * weatherDef)

        defenderUnits.forEach {
            it.attackMultiplier *= (terrainDef * weatherDef)
            it.defenceMultiplier *= (terrainAtk * weatherAtk)
        }

        val randomSeed = (request.attacker.name + request.defender.name + terrainKey + weatherKey).hashCode().toLong()
        val result = battleEngine.resolveBattle(attackerUnit, defenderUnits, city, Random(randomSeed))

        val logs = mutableListOf<String>()
        logs.add("=== 전투 시뮬레이터(BattleEngine) ===")
        logs.add("지형: $terrainKey / 날씨: $weatherKey")
        logs.add("공격: ${attackerGeneral.name} (${attackerGeneral.crew}명)")
        defenders.forEachIndexed { idx, def -> logs.add("방어${idx + 1}: ${def.name} (${def.crew}명)") }
        logs.addAll(result.attackerLogs)

        val attackerRemaining = attackerGeneral.crew.coerceAtLeast(0)
        val defendersRemaining = defenderUnits.map { it.general.crew.coerceAtLeast(0) }
        val totalDefenderRemaining = defendersRemaining.sum()

        val winner = when {
            result.cityOccupied -> "공격측 승리(점령)"
            result.attackerWon && totalDefenderRemaining <= 0 -> "공격측 승리"
            !result.attackerWon && attackerRemaining <= 0 -> "방어측 승리"
            !result.attackerWon -> "방어측 우세"
            else -> "교착 상태"
        }
        logs.add("결과: $winner")

        return SimulateResult(
            winner = winner,
            attackerRemaining = attackerRemaining,
            defenderRemaining = defendersRemaining.firstOrNull() ?: 0,
            defendersRemaining = defendersRemaining,
            rounds = result.attackerLogs.size,
            terrain = terrainKey,
            weather = weatherKey,
            logs = logs,
        )
    }

    private fun toGeneral(info: com.opensam.dto.SimUnitInfo): General {
        return General(
            id = 0,
            worldId = 0,
            name = info.name,
            nationId = info.nationId,
            cityId = 0,
            leadership = info.leadership.toShort(),
            strength = info.strength.toShort(),
            intel = info.intel.toShort(),
            crew = info.crew,
            crewType = info.crewType.toShort(),
            train = info.train.toShort(),
            atmos = info.atmos.toShort(),
            weaponCode = info.weaponCode,
            bookCode = info.bookCode,
            horseCode = info.horseCode,
            specialCode = info.specialCode,
            special2Code = "None",
            rice = 200000,
            gold = 200000,
            expLevel = 5,
            experience = 3000,
            dedication = 3000,
        )
    }
}
