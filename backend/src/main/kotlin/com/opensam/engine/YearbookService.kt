package com.opensam.engine

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.opensam.entity.WorldState
import com.opensam.entity.YearbookHistory
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository
import com.opensam.repository.WorldStateRepository
import com.opensam.repository.YearbookHistoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Service
class YearbookService(
    private val worldStateRepository: WorldStateRepository,
    private val cityRepository: CityRepository,
    private val nationRepository: NationRepository,
    private val generalRepository: GeneralRepository,
    private val yearbookHistoryRepository: YearbookHistoryRepository,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private const val MAP_VERSION = 0
    }

    data class MapCityCompact(
        val id: Long,
        val level: Int,
        val state: Int,
        val nationId: Long,
        val region: Int,
        val supplyFlag: Int,
    )

    data class MapNationCompact(
        val id: Long,
        val name: String,
        val color: String,
        val capitalCityId: Long,
    )

    data class YearbookMap(
        val result: Boolean,
        val version: Int,
        val startYear: Int,
        val year: Int,
        val month: Int,
        val cityList: List<MapCityCompact>,
        val nationList: List<MapNationCompact>,
    )

    data class YearbookNation(
        val id: Long,
        val name: String,
        val color: String,
        val level: Int,
        val power: Int,
        val cities: List<String>,
    )

    @Transactional
    fun saveMonthlySnapshot(worldId: Long, year: Int, month: Int): YearbookHistory {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null)
            ?: throw IllegalArgumentException("World not found: $worldId")

        val map = buildMapSnapshot(world, year, month)
        val nations = buildNationSnapshot(world)
        val hash = buildHash(map, nations)

        val yearShort = year.toShort()
        val monthShort = month.toShort()
        val existing = yearbookHistoryRepository.findByWorldIdAndYearAndMonth(worldId, yearShort, monthShort)
        val entity = existing ?: YearbookHistory(worldId = worldId, year = yearShort, month = monthShort)

        entity.map = objectMapper.convertValue(map, object : TypeReference<MutableMap<String, Any>>() {})
        entity.nations = objectMapper.convertValue(nations, object : TypeReference<MutableList<Map<String, Any>>>() {})
        entity.hash = hash
        entity.updatedAt = OffsetDateTime.now()

        return yearbookHistoryRepository.save(entity)
    }

    private fun buildMapSnapshot(world: WorldState, year: Int, month: Int): YearbookMap {
        val cities = cityRepository.findByWorldId(world.id.toLong())
        val nations = nationRepository.findByWorldId(world.id.toLong())

        val cityList = cities.map { city ->
            val stateInMeta = (city.meta["state"] as? Number)?.toInt()
            val regionInMeta = (city.meta["region"] as? Number)?.toInt()
            MapCityCompact(
                id = city.id,
                level = city.level.toInt(),
                state = stateInMeta ?: city.state.toInt(),
                nationId = city.nationId,
                region = regionInMeta ?: city.region.toInt(),
                supplyFlag = if (city.supplyState.toInt() > 0) 1 else 0,
            )
        }

        val nationList = nations.map { nation ->
            MapNationCompact(
                id = nation.id,
                name = nation.name,
                color = nation.color,
                capitalCityId = nation.capitalCityId ?: 0,
            )
        }

        return YearbookMap(
            result = true,
            version = MAP_VERSION,
            startYear = resolveStartYear(world),
            year = year,
            month = month,
            cityList = cityList,
            nationList = nationList,
        )
    }

    private fun buildNationSnapshot(world: WorldState): List<YearbookNation> {
        val cities = cityRepository.findByWorldId(world.id.toLong())
        val generals = generalRepository.findByWorldId(world.id.toLong())
        val nations = nationRepository.findByWorldId(world.id.toLong())

        data class CityStats(var popSum: Long = 0, var valueSum: Long = 0, var maxSum: Long = 0)
        data class GeneralStats(var goldRice: Long = 0, var statPower: Double = 0.0, var expDed: Long = 0)

        val cityStatsByNation = mutableMapOf<Long, CityStats>()
        val cityNamesByNation = mutableMapOf<Long, MutableList<String>>()

        for (city in cities) {
            val entry = cityStatsByNation.getOrPut(city.nationId) { CityStats() }
            val valueSum = city.pop + city.agri + city.comm + city.secu + city.wall + city.def
            val maxSum = city.popMax + city.agriMax + city.commMax + city.secuMax + city.wallMax + city.defMax
            entry.popSum += city.pop.toLong()
            entry.valueSum += valueSum.toLong()
            entry.maxSum += maxSum.toLong()

            cityNamesByNation.getOrPut(city.nationId) { mutableListOf() }.add(city.name)
        }

        val generalStatsByNation = mutableMapOf<Long, GeneralStats>()
        for (general in generals) {
            val entry = generalStatsByNation.getOrPut(general.nationId) { GeneralStats() }
            entry.goldRice += (general.gold + general.rice).toLong()

            val leadership = general.leadership.toDouble()
            val strength = general.strength.toDouble()
            val intel = general.intel.toDouble()
            val npcMultiplier = if (general.npcState < 2) 1.2 else 1.0
            val leaderCore = if (leadership >= 40) leadership else 0.0

            entry.statPower += npcMultiplier * leaderCore * 2 + (sqrt(intel * strength) * 2 + leadership / 2) / 2
            entry.expDed += (general.experience + general.dedication).toLong()
        }

        return nations.map { nation ->
            val generalStats = generalStatsByNation[nation.id] ?: GeneralStats()
            val cityStats = cityStatsByNation[nation.id] ?: CityStats()

            val resource = (((nation.gold + nation.rice).toLong() + generalStats.goldRice) / 100.0).roundToInt()
            val tech = ((nation.meta["tech"] as? Number)?.toDouble() ?: nation.tech.toDouble()).roundToInt()
            val cityPower = if (nation.level.toInt() > 0 && cityStats.maxSum > 0L) {
                ((cityStats.popSum * cityStats.valueSum).toDouble() / cityStats.maxSum / 100.0).roundToInt()
            } else {
                0
            }
            val expDed = (generalStats.expDed / 100.0).roundToInt()
            val power = ((resource + tech + cityPower + generalStats.statPower + expDed) / 10.0).roundToInt()

            YearbookNation(
                id = nation.id,
                name = nation.name,
                color = nation.color,
                level = nation.level.toInt(),
                power = power,
                cities = cityNamesByNation[nation.id] ?: emptyList(),
            )
        }
    }

    private fun resolveStartYear(world: WorldState): Int {
        val scenarioMeta = world.meta["scenarioMeta"] as? Map<*, *>
        val fromMeta = (scenarioMeta?.get("startYear") as? Number)?.toInt()
        if (fromMeta != null) {
            return fromMeta
        }

        val fromConfigUpper = (world.config["startYear"] as? Number)?.toInt()
        if (fromConfigUpper != null) {
            return fromConfigUpper
        }

        val fromConfigLower = (world.config["startyear"] as? Number)?.toInt()
        if (fromConfigLower != null) {
            return fromConfigLower
        }

        return 0
    }

    private fun buildHash(map: YearbookMap, nations: List<YearbookNation>): String {
        val payload = objectMapper.writeValueAsString(mapOf("map" to map, "nations" to nations))
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
