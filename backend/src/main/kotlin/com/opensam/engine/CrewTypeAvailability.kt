package com.opensam.engine

import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import org.springframework.stereotype.Service
import kotlin.math.floor

private val DEFAULT_REGION_MAP: Map<String, Int> = mapOf(
    "하북" to 1,
    "중원" to 2,
    "서북" to 3,
    "서촉" to 4,
    "남중" to 5,
    "초" to 6,
    "오월" to 7,
    "동이" to 8,
)

data class MapCityDefinition(
    val id: Int,
    val name: String,
    val level: Int,
    val region: Int,
)

sealed class CrewTypeRequirement {
    data class ReqTech(val tech: Int) : CrewTypeRequirement()

    data class ReqRegions(val regions: List<String>) : CrewTypeRequirement()

    data class ReqCities(val cities: List<String>) : CrewTypeRequirement()

    data class ReqCitiesWithCityLevel(
        val level: Int,
        val cities: List<String>,
    ) : CrewTypeRequirement()

    data class ReqHighLevelCities(
        val level: Int,
        val count: Int,
    ) : CrewTypeRequirement()

    data class ReqNationAux(
        val key: String,
        val op: String,
        val value: Any,
    ) : CrewTypeRequirement()

    data class ReqMinRelYear(val year: Int) : CrewTypeRequirement()

    data object ReqChief : CrewTypeRequirement()

    data object ReqNotChief : CrewTypeRequirement()

    data object Impossible : CrewTypeRequirement()
}

data class CrewTypeTriggers(
    val initSkillTrigger: List<String>?,
    val phaseSkillTrigger: List<String>?,
    val iActionList: List<String>?,
)

data class CrewTypeDefinition(
    val id: Int,
    val armType: Int,
    val name: String,
    val attack: Int,
    val defence: Int,
    val speed: Int,
    val avoid: Int,
    val magicCoef: Double,
    val cost: Int,
    val rice: Int,
    val requirements: List<CrewTypeRequirement>,
    val attackCoef: Map<String, Double>,
    val defenceCoef: Map<String, Double>,
    val info: List<String>,
    val triggers: CrewTypeTriggers,
)

data class UnitSetDefinition(
    val id: String,
    val name: String,
    val crewTypes: List<CrewTypeDefinition>,
    val defaultCrewTypeId: Int? = null,
)

data class CrewTypeAvailabilityContext(
    val general: General,
    val nation: Nation?,
    val mapCities: List<MapCityDefinition>,
    val ownedCities: List<City>,
    val currentYear: Int? = null,
    val startYear: Int? = null,
    val regionMap: Map<String, Int> = emptyMap(),
)

@Service
class CrewTypeAvailability {

    fun parseUnitSetDefinition(data: Map<String, Any?>): UnitSetDefinition {
        val id = data["id"].asString("unknown")
        val name = data["name"].asString(id)
        val defaultCrewTypeId = data["defaultCrewTypeId"].asIntOrNull()
        val crewTypes = data["crewTypes"].asList().mapNotNull { parseCrewType(it.asStringMap()) }

        return UnitSetDefinition(
            id = id,
            name = name,
            crewTypes = crewTypes,
            defaultCrewTypeId = defaultCrewTypeId,
        )
    }

    fun isCrewTypeAvailable(
        unitSet: UnitSetDefinition,
        crewTypeId: Int,
        context: CrewTypeAvailabilityContext,
    ): Boolean {
        val crewType = unitSet.crewTypes.firstOrNull { it.id == crewTypeId } ?: return false

        val nationId = context.nation?.id ?: context.general.nationId
        val ownedCities = context.ownedCities.filter { it.nationId == nationId }
        val ownedCityMap = ownedCities.associateBy { it.id.toInt() }

        val nameToId = context.mapCities.associate { it.name to it.id }
        val regionByCityId = context.mapCities.associate { it.id to it.region }
        val ownedRegions = ownedCities.mapNotNull { regionByCityId[it.id.toInt()] }.toSet()

        val regionMap = buildRegionMap(context)
        val tech = resolveNationTech(context.nation)
        val relYear = resolveRelativeYear(context)
        val aux = resolveNationAux(context.nation)

        for (requirement in crewType.requirements) {
            when (requirement) {
                is CrewTypeRequirement.ReqTech -> {
                    if (tech < requirement.tech) {
                        return false
                    }
                }

                is CrewTypeRequirement.ReqRegions -> {
                    val requiredRegionIds = requirement.regions.mapNotNull { regionMap[it] }
                    if (requiredRegionIds.none { it in ownedRegions }) {
                        return false
                    }
                }

                is CrewTypeRequirement.ReqCities -> {
                    val requiredCityIds = requirement.cities.mapNotNull { nameToId[it] }
                    if (requiredCityIds.none { it in ownedCityMap }) {
                        return false
                    }
                }

                is CrewTypeRequirement.ReqCitiesWithCityLevel -> {
                    val requiredCityIds = requirement.cities.mapNotNull { nameToId[it] }
                    if (
                        requiredCityIds.none { cityId ->
                            val city = ownedCityMap[cityId]
                            city != null && city.level.toInt() >= requirement.level
                        }
                    ) {
                        return false
                    }
                }

                is CrewTypeRequirement.ReqHighLevelCities -> {
                    val count = ownedCities.count { it.level.toInt() >= requirement.level }
                    if (count < requirement.count) {
                        return false
                    }
                }

                is CrewTypeRequirement.ReqNationAux -> {
                    val actual = aux[requirement.key]
                    if (!compareAuxValue(actual, requirement.op, requirement.value)) {
                        return false
                    }
                }

                is CrewTypeRequirement.ReqMinRelYear -> {
                    if (relYear < requirement.year) {
                        return false
                    }
                }

                CrewTypeRequirement.ReqChief -> {
                    if (context.general.officerLevel.toInt() <= 4) {
                        return false
                    }
                }

                CrewTypeRequirement.ReqNotChief -> {
                    if (context.general.officerLevel.toInt() > 4) {
                        return false
                    }
                }

                CrewTypeRequirement.Impossible -> return false
            }
        }

        return true
    }

    fun getTechLevel(tech: Int, maxLevel: Int = 12): Int {
        val level = floor(tech / 1000.0).toInt()
        return level.coerceIn(0, maxLevel)
    }

    fun getTechCost(tech: Int): Double = 1 + getTechLevel(tech) * 0.15

    fun buildCrewTypeIndex(unitSet: UnitSetDefinition): Map<Int, CrewTypeDefinition> =
        unitSet.crewTypes.associateBy { it.id }

    private fun parseCrewType(raw: Map<String, Any?>): CrewTypeDefinition? {
        val id = raw["id"].asInt(0)
        if (id <= 0) {
            return null
        }

        return CrewTypeDefinition(
            id = id,
            armType = raw["armType"].asInt(0),
            name = raw["name"].asString(""),
            attack = raw["attack"].asInt(0),
            defence = raw["defence"].asInt(0),
            speed = raw["speed"].asInt(0),
            avoid = raw["avoid"].asInt(0),
            magicCoef = raw["magicCoef"].asDouble(0.0),
            cost = raw["cost"].asInt(0),
            rice = raw["rice"].asInt(0),
            requirements = raw["requirements"].asList().mapNotNull { parseRequirement(it.asStringMap()) },
            attackCoef = normalizeCoef(raw["attackCoef"]),
            defenceCoef = normalizeCoef(raw["defenceCoef"]),
            info = raw["info"].asStringList(),
            triggers = CrewTypeTriggers(
                initSkillTrigger = raw["initSkillTrigger"].asNullableStringList(),
                phaseSkillTrigger = raw["phaseSkillTrigger"].asNullableStringList(),
                iActionList = raw["iActionList"].asNullableStringList(),
            ),
        )
    }

    private fun parseRequirement(raw: Map<String, Any?>): CrewTypeRequirement? {
        return when (raw["type"].asString("")) {
            "ReqTech" -> CrewTypeRequirement.ReqTech(raw["tech"].asInt(0))
            "ReqRegions" -> CrewTypeRequirement.ReqRegions(raw["regions"].asStringList())
            "ReqCities" -> CrewTypeRequirement.ReqCities(raw["cities"].asStringList())
            "ReqCitiesWithCityLevel" -> CrewTypeRequirement.ReqCitiesWithCityLevel(
                level = raw["level"].asInt(0),
                cities = raw["cities"].asStringList(),
            )

            "ReqHighLevelCities" -> CrewTypeRequirement.ReqHighLevelCities(
                level = raw["level"].asInt(0),
                count = raw["count"].asInt(0),
            )

            "ReqNationAux" -> CrewTypeRequirement.ReqNationAux(
                key = raw["key"].asString(""),
                op = raw["op"].asString("=="),
                value = raw["value"] ?: 0,
            )

            "ReqMinRelYear" -> CrewTypeRequirement.ReqMinRelYear(raw["year"].asInt(0))
            "ReqChief" -> CrewTypeRequirement.ReqChief
            "ReqNotChief" -> CrewTypeRequirement.ReqNotChief
            "Impossible" -> CrewTypeRequirement.Impossible
            else -> CrewTypeRequirement.Impossible
        }
    }

    private fun normalizeCoef(value: Any?): Map<String, Double> {
        val raw = value as? Map<*, *> ?: return emptyMap()
        val result = mutableMapOf<String, Double>()
        for ((key, entry) in raw) {
            val stringKey = key?.toString() ?: continue
            val number = (entry as? Number)?.toDouble() ?: continue
            if (number.isFinite()) {
                result[stringKey] = number
            }
        }
        return result
    }

    private fun resolveNationTech(nation: Nation?): Int {
        if (nation == null) {
            return 0
        }
        val fromMeta = (nation.meta["tech"] as? Number)?.toInt()
        return fromMeta ?: nation.tech.toInt()
    }

    private fun resolveNationAux(nation: Nation?): Map<String, Any?> {
        if (nation == null) {
            return emptyMap()
        }
        val aux = nation.meta["aux"] as? Map<*, *>
        if (aux != null && aux.isNotEmpty()) {
            return aux.entries.mapNotNull { (key, value) ->
                val stringKey = key as? String ?: return@mapNotNull null
                stringKey to value
            }.toMap()
        }
        return nation.meta
    }

    private fun resolveRelativeYear(context: CrewTypeAvailabilityContext): Int {
        val currentYear = context.currentYear
        val startYear = context.startYear
        if (currentYear == null || startYear == null) {
            return 0
        }
        return (currentYear - startYear).coerceAtLeast(0)
    }

    private fun buildRegionMap(context: CrewTypeAvailabilityContext): Map<String, Int> {
        val merged = DEFAULT_REGION_MAP.toMutableMap()
        merged.putAll(context.regionMap)
        for (city in context.mapCities) {
            merged.putIfAbsent(city.name, city.region)
        }
        return merged
    }

    private fun compareAuxValue(actual: Any?, op: String, expected: Any): Boolean {
        val actualNumber = actual.toComparableNumber()
        val expectedNumber = expected.toComparableNumber()

        if (actualNumber != null && expectedNumber != null) {
            return when (op) {
                "==" -> actualNumber == expectedNumber
                "!=" -> actualNumber != expectedNumber
                ">=" -> actualNumber >= expectedNumber
                "<=" -> actualNumber <= expectedNumber
                ">" -> actualNumber > expectedNumber
                "<" -> actualNumber < expectedNumber
                else -> actualNumber == expectedNumber
            }
        }

        if (op == "==" || op == "!=") {
            val actualString = actual?.toString().orEmpty()
            val expectedString = expected.toString()
            return if (op == "==") actualString == expectedString else actualString != expectedString
        }

        return false
    }
}

private fun Any?.asInt(default: Int): Int = when (this) {
    is Int -> this
    is Long -> this.toInt()
    is Short -> this.toInt()
    is Float -> this.toInt()
    is Double -> this.toInt()
    is String -> this.toIntOrNull() ?: default
    else -> default
}

private fun Any?.asIntOrNull(): Int? = when (this) {
    is Int -> this
    is Long -> this.toInt()
    is Short -> this.toInt()
    is Float -> this.toInt()
    is Double -> this.toInt()
    is String -> this.toIntOrNull()
    else -> null
}

private fun Any?.asDouble(default: Double): Double = when (this) {
    is Number -> this.toDouble()
    is String -> this.toDoubleOrNull() ?: default
    else -> default
}

private fun Any?.asString(default: String): String = this as? String ?: default

private fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()

private fun Any?.asStringMap(): Map<String, Any?> {
    val value = this as? Map<*, *> ?: return emptyMap()
    return value.entries.mapNotNull { (key, entry) ->
        val stringKey = key as? String ?: return@mapNotNull null
        stringKey to entry
    }.toMap()
}

private fun Any?.asStringList(): List<String> {
    val list = this as? List<*> ?: return emptyList()
    return list.mapNotNull { it as? String }
}

private fun Any?.asNullableStringList(): List<String>? {
    if (this == null) {
        return null
    }
    return asStringList()
}

private fun Any?.toComparableNumber(): Double? = when (this) {
    is Number -> this.toDouble()
    is String -> this.toDoubleOrNull()
    else -> null
}
