package com.opensam.engine.war

import com.opensam.engine.DeterministicRng
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import com.opensam.model.CrewType
import org.springframework.stereotype.Service
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.round
import kotlin.random.Random

private const val META_DEAD = "dead"
private const val META_CONFLICT = "conflict"

data class WarTimeContext(
    val year: Int,
    val month: Int,
    val startYear: Int,
)

data class WarUnitReport(
    val id: Long?,
    val type: String,
    val name: String,
    val isAttacker: Boolean,
    val killed: Int,
    val dead: Int,
)

data class WarBattleOutcome(
    val attacker: General,
    val defenders: List<General>,
    val defenderCity: City,
    val logs: List<String>,
    val conquered: Boolean,
    val reports: List<WarUnitReport>,
)

data class WarAftermathConfig(
    val initialNationGenLimit: Int,
    val techLevelIncYear: Int,
    val initialAllowedTechLevel: Int,
    val maxTechLevel: Int,
    val defaultCityWall: Int,
    val baseGold: Int,
    val baseRice: Int,
    val castleCrewTypeId: Int,
)

data class WarAftermathTechContext(
    val side: String,
    val nation: Nation,
    val attackerReport: WarUnitReport,
    val baseGain: Double,
)

data class WarAftermathInput(
    val battle: WarBattleOutcome,
    val attackerNation: Nation,
    val defenderNation: Nation?,
    val attackerCity: City,
    val defenderCity: City,
    val nations: List<Nation>,
    val cities: List<City>,
    val generals: List<General>,
    val config: WarAftermathConfig,
    val time: WarTimeContext,
    val hiddenSeed: String? = null,
    val rng: Random? = null,
    val calcNationTechGain: ((WarAftermathTechContext) -> Double)? = null,
)

data class WarDiplomacyDelta(
    val fromNationId: Long,
    val toNationId: Long,
    val deadDelta: Int,
)

data class ConquerCityOutcome(
    val conquerNationId: Long,
    val nationCollapsed: Boolean,
    val collapseRewardGold: Int,
    val collapseRewardRice: Int,
    val logs: List<String>,
    val nations: List<Nation>,
    val cities: List<City>,
    val generals: List<General>,
)

data class WarAftermathOutcome(
    val nations: List<Nation>,
    val cities: List<City>,
    val generals: List<General>,
    val diplomacyDeltas: List<WarDiplomacyDelta>,
    val logs: List<String>,
    val conquered: Boolean,
    val conquest: ConquerCityOutcome? = null,
)

@Service
class WarAftermath {

    companion object {
        fun getTechLevel(tech: Double, maxLevel: Int): Int {
            if (!tech.isFinite()) {
                return 0
            }
            return floor(tech / 1000.0).toInt().coerceIn(0, maxLevel)
        }

        fun getTechCost(tech: Double): Double = 1.0 + getTechLevel(tech, 12) * 0.15
    }

    fun resolveWarAftermath(input: WarAftermathInput): WarAftermathOutcome {
        val logs = mutableListOf<String>()
        val diplomacyDeltas = mutableListOf<WarDiplomacyDelta>()
        val affectedNations = linkedSetOf<Nation>()
        val affectedCities = linkedSetOf<City>()
        val affectedGenerals = linkedSetOf<General>()

        val attackerReport = findReport(input.battle.reports) { it.type == "general" && it.isAttacker }
        val cityReport = findReport(input.battle.reports) { it.type == "city" }

        val attackerKilled = attackerReport?.killed ?: 0
        val attackerDead = attackerReport?.dead ?: 0
        val totalDead = attackerKilled + attackerDead

        if (totalDead > 0) {
            val attackerCityDead = getDeadCounter(input.attackerCity) + totalDead * 0.4
            val defenderCityDead = getDeadCounter(input.defenderCity) + totalDead * 0.6
            setDeadCounter(input.attackerCity, attackerCityDead)
            setDeadCounter(input.defenderCity, defenderCityDead)
            affectedCities.add(input.attackerCity)
            affectedCities.add(input.defenderCity)
        }

        if (input.defenderNation != null && input.defenderNation.id != 0L && isSupplyCity(input.defenderCity)) {
            val defenderNation = input.defenderNation
            val cityKilled = cityReport?.killed ?: 0

            if ((cityReport?.dead ?: 0) > 0) {
                val crewType = CrewType.fromCode(input.config.castleCrewTypeId)
                val riceCoef = crewType?.riceCost?.toDouble() ?: 1.0

                var rice = (cityKilled / 100.0) * 0.8
                rice *= riceCoef
                rice *= getTechCost(getNationTech(defenderNation))
                rice *= resolveCityTrainAtmos(input.time.year, input.time.startYear) / 100.0 - 0.2
                rice = round(rice)

                defenderNation.rice = (defenderNation.rice - rice.toInt()).coerceAtLeast(0)
                affectedNations.add(defenderNation)
            } else if (input.battle.conquered) {
                val bonus = if (defenderNation.capitalCityId == input.defenderCity.id) 1000 else 500
                defenderNation.rice = round(defenderNation.rice + bonus.toDouble()).toInt()
                affectedNations.add(defenderNation)
            }
        }

        if (input.attackerNation.id != 0L && attackerReport != null) {
            val attackerTechGain = attackerDead * 0.012
            applyNationTechGain(
                nation = input.attackerNation,
                baseGain = attackerTechGain,
                input = input,
                context = WarAftermathTechContext(
                    side = "attacker",
                    nation = input.attackerNation,
                    attackerReport = attackerReport,
                    baseGain = attackerTechGain,
                ),
            )
            affectedNations.add(input.attackerNation)
        }

        if (input.defenderNation != null && input.defenderNation.id != 0L && attackerReport != null) {
            val defenderTechGain = attackerKilled * 0.009
            applyNationTechGain(
                nation = input.defenderNation,
                baseGain = defenderTechGain,
                input = input,
                context = WarAftermathTechContext(
                    side = "defender",
                    nation = input.defenderNation,
                    attackerReport = attackerReport,
                    baseGain = defenderTechGain,
                ),
            )
            affectedNations.add(input.defenderNation)

            diplomacyDeltas.add(
                WarDiplomacyDelta(
                    fromNationId = input.attackerNation.id,
                    toNationId = input.defenderNation.id,
                    deadDelta = round(attackerDead.toDouble()).toInt(),
                ),
            )
            diplomacyDeltas.add(
                WarDiplomacyDelta(
                    fromNationId = input.defenderNation.id,
                    toNationId = input.attackerNation.id,
                    deadDelta = round(attackerKilled.toDouble()).toInt(),
                ),
            )
        }

        var conquest: ConquerCityOutcome? = null
        if (input.battle.conquered) {
            val rng = input.rng ?: DeterministicRng.create(
                input.hiddenSeed ?: "",
                "ConquerCity",
                input.time.year,
                input.time.month,
                input.attackerNation.id,
                input.battle.attacker.id,
                input.defenderCity.id,
            )
            conquest = resolveConquerCity(input, rng)
            logs.addAll(conquest.logs)
            affectedNations.addAll(conquest.nations)
            affectedCities.addAll(conquest.cities)
            affectedGenerals.addAll(conquest.generals)
        }

        return WarAftermathOutcome(
            nations = affectedNations.toList(),
            cities = affectedCities.toList(),
            generals = affectedGenerals.toList(),
            diplomacyDeltas = diplomacyDeltas,
            logs = logs,
            conquered = input.battle.conquered,
            conquest = conquest,
        )
    }

    private fun resolveConquerCity(input: WarAftermathInput, rng: Random): ConquerCityOutcome {
        val attackerNation = input.attackerNation
        val defenderNation = input.defenderNation
        val defenderCity = input.defenderCity
        val attacker = input.battle.attacker

        val logs = mutableListOf<String>()
        val affectedCities = linkedSetOf<City>()
        val affectedGenerals = linkedSetOf<General>()
        val affectedNations = linkedSetOf<Nation>()

        val conquerNationId = resolveConquerNation(defenderCity, attackerNation.id)
        logs.add("${defenderCity.name} 공략 성공")
        logs.add("${defenderCity.name} 점령")

        val defenderNationId = defenderNation?.id ?: 0L
        val defenderCityCount = if (defenderNationId != 0L) {
            input.cities.count { it.nationId == defenderNationId }
        } else {
            0
        }
        val nationCollapsed = defenderNationId != 0L && defenderCityCount == 1

        var collapseRewardGold = 0.0
        var collapseRewardRice = 0.0

        if (nationCollapsed && defenderNation != null) {
            val defenderGenerals = input.generals.filter { it.nationId == defenderNationId }
            var totalGoldLoss = 0
            var totalRiceLoss = 0

            for (general in defenderGenerals) {
                val loseGold = round(general.gold * rng.nextDouble(0.2, 0.5)).toInt()
                val loseRice = round(general.rice * rng.nextDouble(0.2, 0.5)).toInt()
                general.gold = (general.gold - loseGold).coerceAtLeast(0)
                general.rice = (general.rice - loseRice).coerceAtLeast(0)
                general.experience = round(general.experience * 0.9).toInt()
                general.dedication = round(general.dedication * 0.5).toInt()

                totalGoldLoss += loseGold
                totalRiceLoss += loseRice
                logs.add("${general.name}: 도주하며 금${loseGold} 쌀${loseRice} 분실")
                affectedGenerals.add(general)
            }

            collapseRewardGold = (defenderNation.gold - input.config.baseGold).coerceAtLeast(0) * 0.5 + totalGoldLoss * 0.5
            collapseRewardRice = (defenderNation.rice - input.config.baseRice).coerceAtLeast(0) * 0.5 + totalRiceLoss * 0.5

            attackerNation.gold = round(attackerNation.gold + collapseRewardGold).toInt()
            attackerNation.rice = round(attackerNation.rice + collapseRewardRice).toInt()

            defenderNation.meta["collapsed"] = true
            affectedNations.add(defenderNation)
            affectedNations.add(attackerNation)
        }

        if (!nationCollapsed && defenderNation != null && defenderNation.capitalCityId == defenderCity.id) {
            val nextCapital = findNextCapital(
                cities = input.cities,
                defenderNationId = defenderNationId,
                capturedCityId = defenderCity.id,
                oldCapital = defenderCity,
            )
            if (nextCapital != null) {
                defenderNation.capitalCityId = nextCapital.id
                defenderNation.gold = round(defenderNation.gold * 0.5).toInt()
                defenderNation.rice = round(defenderNation.rice * 0.5).toInt()

                nextCapital.supplyState = 1
                affectedCities.add(nextCapital)

                for (general in input.generals) {
                    if (general.nationId != defenderNationId) {
                        continue
                    }
                    general.atmos = round(general.atmos * 0.8).toInt().toShort()
                    if (general.officerLevel >= 5) {
                        general.cityId = nextCapital.id
                    }
                    affectedGenerals.add(general)
                }

                affectedNations.add(defenderNation)
            }
        }

        val conquerNation = if (conquerNationId == attackerNation.id) {
            attackerNation
        } else {
            input.nations.find { it.id == conquerNationId } ?: attackerNation
        }

        if (conquerNationId == attackerNation.id) {
            attacker.cityId = defenderCity.id
            affectedGenerals.add(attacker)
        } else {
            logs.add("분쟁협상으로 ${defenderCity.name} 양도")
        }

        defenderCity.supplyState = 1
        defenderCity.frontState = 0
        defenderCity.agri = round(defenderCity.agri * 0.7).toInt()
        defenderCity.comm = round(defenderCity.comm * 0.7).toInt()
        defenderCity.secu = round(defenderCity.secu * 0.7).toInt()
        defenderCity.nationId = conquerNationId
        defenderCity.meta[META_CONFLICT] = "{}"
        defenderCity.conflict = mutableMapOf()

        if (defenderCity.level > 3) {
            defenderCity.def = input.config.defaultCityWall
            defenderCity.wall = input.config.defaultCityWall
        } else {
            defenderCity.def = round(defenderCity.defMax / 2.0).toInt()
            defenderCity.wall = round(defenderCity.wallMax / 2.0).toInt()
        }

        affectedCities.add(defenderCity)
        affectedNations.add(conquerNation)

        return ConquerCityOutcome(
            conquerNationId = conquerNationId,
            nationCollapsed = nationCollapsed,
            collapseRewardGold = round(collapseRewardGold).toInt(),
            collapseRewardRice = round(collapseRewardRice).toInt(),
            logs = logs,
            nations = affectedNations.toList(),
            cities = affectedCities.toList(),
            generals = affectedGenerals.toList(),
        )
    }

    private fun findReport(reports: List<WarUnitReport>, predicate: (WarUnitReport) -> Boolean): WarUnitReport? {
        for (report in reports) {
            if (predicate(report)) {
                return report
            }
        }
        return null
    }

    private fun getDeadCounter(city: City): Int = getMetaNumber(city.meta, META_DEAD, 0.0).toInt()

    private fun setDeadCounter(city: City, value: Double) {
        city.meta[META_DEAD] = round(value).toInt()
    }

    private fun isSupplyCity(city: City): Boolean {
        val raw = city.meta["supply"]
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toDouble() > 0
            else -> city.supplyState > 0
        }
    }

    private fun resolveCityTrainAtmos(year: Int, startYear: Int): Int = (year - startYear + 59).coerceIn(60, 110)

    private fun isTechLimited(tech: Double, year: Int, startYear: Int, config: WarAftermathConfig): Boolean {
        val relYear = (year - startYear).coerceAtLeast(0)
        val relMaxTech = (floor(relYear / config.techLevelIncYear.toDouble()).toInt() + config.initialAllowedTechLevel)
            .coerceIn(1, config.maxTechLevel)
        val techLevel = getTechLevel(tech, config.maxTechLevel)
        return techLevel >= relMaxTech
    }

    private fun resolveNationGenCount(
        nation: Nation,
        generals: List<General>,
        config: WarAftermathConfig,
    ): Pair<Int, Int> {
        val fallback = generals.count { it.nationId == nation.id }
        var total = getMetaNumber(nation.meta, "gennum", fallback.toDouble()).toInt()
        var effective = generals.count { it.nationId == nation.id && it.npcState.toInt() != 5 }

        if (effective < config.initialNationGenLimit) {
            total = config.initialNationGenLimit
            effective = config.initialNationGenLimit
        }

        return total to effective
    }

    private fun applyNationTechGain(
        nation: Nation,
        baseGain: Double,
        input: WarAftermathInput,
        context: WarAftermathTechContext,
    ) {
        var gain = baseGain
        if (input.calcNationTechGain != null) {
            gain = input.calcNationTechGain.invoke(context.copy(baseGain = gain))
        }

        val (total, effective) = resolveNationGenCount(nation, input.generals, input.config)
        if (total != effective) {
            gain *= total.toDouble() / effective.toDouble()
        }

        val currentTech = getNationTech(nation)
        if (isTechLimited(currentTech, input.time.year, input.time.startYear, input.config)) {
            gain /= 4.0
        }

        val divisor = maxOf(input.config.initialNationGenLimit, total)
        val nextTech = currentTech + gain / divisor.toDouble()
        setNationTech(nation, round(nextTech))
    }

    private fun resolveConquerNation(city: City, attackerNationId: Long): Long {
        val rawConflict = city.meta[META_CONFLICT] ?: return attackerNationId
        return try {
            val parsed = parseConflict(rawConflict.toString())
            if (parsed.isEmpty()) {
                attackerNationId
            } else {
                parsed.maxByOrNull { it.value }?.key ?: attackerNationId
            }
        } catch (_: Exception) {
            attackerNationId
        }
    }

    private fun findNextCapital(
        cities: List<City>,
        defenderNationId: Long,
        capturedCityId: Long,
        oldCapital: City,
    ): City? {
        val candidates = cities.filter { it.nationId == defenderNationId && it.id != capturedCityId }
        if (candidates.isEmpty()) {
            return null
        }

        val oldPos = getCityPosition(oldCapital)
        if (oldPos == null) {
            return candidates.maxByOrNull { it.pop }
        }

        return candidates.minWithOrNull(
            compareBy<City> {
                val pos = getCityPosition(it)
                if (pos == null) {
                    Double.MAX_VALUE
                } else {
                    hypot(pos.first - oldPos.first, pos.second - oldPos.second)
                }
            }.thenByDescending { it.pop },
        )
    }

    private fun getCityPosition(city: City): Pair<Double, Double>? {
        val x = getMetaNumber(city.meta, "positionX", Double.NaN)
        val y = getMetaNumber(city.meta, "positionY", Double.NaN)
        if (!x.isFinite() || !y.isFinite()) {
            return null
        }
        return x to y
    }

    private fun getMetaNumber(meta: Map<String, Any>, key: String, fallback: Double): Double {
        val value = meta[key] ?: return fallback
        return if (value is Number && value.toDouble().isFinite()) value.toDouble() else fallback
    }

    private fun getNationTech(nation: Nation): Double {
        val techMeta = nation.meta["tech"]
        if (techMeta is Number) {
            return techMeta.toDouble()
        }
        return nation.tech.toDouble()
    }

    private fun setNationTech(nation: Nation, tech: Double) {
        nation.meta["tech"] = tech.toInt()
        nation.tech = tech.toFloat()
    }

    private fun parseConflict(raw: String): Map<Long, Int> {
        val trimmed = raw.trim().removePrefix("{").removeSuffix("}").trim()
        if (trimmed.isBlank()) {
            return emptyMap()
        }
        val result = mutableMapOf<Long, Int>()
        for (entry in trimmed.split(',')) {
            val parts = entry.split(':', limit = 2)
            if (parts.size != 2) {
                continue
            }
            val key = parts[0].trim().removePrefix("\"").removeSuffix("\"").toLongOrNull() ?: continue
            val value = parts[1].trim().toIntOrNull() ?: continue
            result[key] = value
        }
        return result
    }
}
