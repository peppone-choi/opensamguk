package com.opensam.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.opensam.entity.*
import com.opensam.model.ScenarioData
import com.opensam.model.ScenarioInfo
import com.opensam.repository.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class ScenarioService(
    private val objectMapper: ObjectMapper,
    @Value("\${game.commit-sha:local}") private val defaultCommitSha: String,
    @Value("\${game.version:dev}") private val defaultGameVersion: String,
    private val worldStateRepository: WorldStateRepository,
    private val nationRepository: NationRepository,
    private val cityRepository: CityRepository,
    private val generalRepository: GeneralRepository,
    private val diplomacyRepository: DiplomacyRepository,
    private val mapService: MapService,
) {
    private val scenarios = mutableMapOf<String, ScenarioData>()

    fun listScenarios(): List<ScenarioInfo> {
        loadAllScenarios()
        return scenarios.map { (code, data) ->
            ScenarioInfo(code, data.title, data.startYear)
        }.sortedBy { it.code }
    }

    fun getScenario(code: String): ScenarioData {
        loadAllScenarios()
        return scenarios[code] ?: throw IllegalArgumentException("Scenario not found: $code")
    }

    @Transactional
    fun initializeWorld(
        scenarioCode: String,
        tickSeconds: Int = 300,
        commitSha: String? = null,
        gameVersion: String? = null,
    ): WorldState {
        val scenario = getScenario(scenarioCode)
        val resolvedCommitSha = commitSha?.takeIf { it.isNotBlank() } ?: defaultCommitSha
        val resolvedGameVersion = gameVersion?.takeIf { it.isNotBlank() } ?: defaultGameVersion

        val mapName = scenario.map?.mapName ?: "che"
        val world = worldStateRepository.save(
            WorldState(
                scenarioCode = scenarioCode,
                commitSha = resolvedCommitSha,
                gameVersion = resolvedGameVersion,
                currentYear = scenario.startYear.toShort(),
                currentMonth = 1,
                tickSeconds = tickSeconds,
                config = mutableMapOf(
                    "mapCode" to mapName,
                    "startyear" to scenario.startYear,
                    "hiddenSeed" to java.util.UUID.randomUUID().toString(),
                ),
            )
        )
        val worldId = world.id.toLong()

        // 1. Create cities from map data
        val mapCities = try { mapService.getCities(mapName) } catch (_: Exception) { mapService.getCities("che") }
        val cityNameToId = mutableMapOf<String, Long>()
        for (mc in mapCities) {
            val city = cityRepository.save(
                City(
                    worldId = worldId,
                    name = mc.name,
                    level = mc.level.toShort(),
                    pop = mc.population,
                    popMax = mc.population,
                    agri = mc.agriculture,
                    agriMax = mc.agriculture,
                    comm = mc.commerce,
                    commMax = mc.commerce,
                    secu = mc.security,
                    secuMax = mc.security,
                    def = mc.defence,
                    defMax = mc.defence,
                    wall = mc.wall,
                    wallMax = mc.wall,
                    region = mc.region.toShort(),
                )
            )
            cityNameToId[mc.name] = city.id
        }

        // 2. Create nations and assign cities
        // nationIdx is 1-based: nations[0] -> idx=1, nations[1] -> idx=2, ...
        val nationIdxToDbId = mutableMapOf<Int, Long>() // 1-based scenario idx -> DB nation id
        val nationCityIds = mutableMapOf<Long, MutableList<Long>>() // DB nation id -> city IDs
        for ((idx, nationRow) in scenario.nation.withIndex()) {
            val nationIdx = idx + 1 // 1-based
            val nation = parseNation(nationRow, worldId)

            // Set capital from first city in nation's city list
            @Suppress("UNCHECKED_CAST")
            val nationCityNames = (nationRow.getOrNull(8) as? List<String>) ?: emptyList()
            val nationCities = nationCityNames.mapNotNull { cityNameToId[it] }
            if (nationCities.isNotEmpty()) {
                nation.capitalCityId = nationCities.first()
            }

            val saved = nationRepository.save(nation)
            nationIdxToDbId[nationIdx] = saved.id
            nationCityIds[saved.id] = nationCities.toMutableList()

            // Update cities to belong to this nation
            for (cid in nationCities) {
                cityRepository.findById(cid).ifPresent { c ->
                    c.nationId = saved.id
                    cityRepository.save(c)
                }
            }
        }

        // 3. Create generals (등장)
        for (generalRow in scenario.general) {
            val general = parseGeneral(generalRow, worldId, nationIdxToDbId, nationCityIds, scenario.startYear, appeared = true)
            generalRepository.save(general)
        }

        // 4. Create generals_ex (확장 - 미등장)
        for (generalRow in scenario.generalEx) {
            val general = parseGeneral(generalRow, worldId, nationIdxToDbId, nationCityIds, scenario.startYear, appeared = false)
            generalRepository.save(general)
        }

        // 4.5. Assign chief generals (officerLevel >= 12 → ruler)
        for ((nationIdx, nationDbId) in nationIdxToDbId) {
            val ruler = generalRepository.findByWorldId(worldId)
                .filter { it.nationId == nationDbId && it.officerLevel >= 12 }
                .maxByOrNull { it.officerLevel }
            if (ruler != null) {
                val nation = nationRepository.findById(nationDbId).orElse(null)
                if (nation != null) {
                    nation.chiefGeneralId = ruler.id
                    nationRepository.save(nation)
                }
            }
        }

        // 5. Create diplomacy
        val nationDbIds = nationIdxToDbId.values.toList()
        for (diploRow in scenario.diplomacy) {
            if (diploRow.size >= 4) {
                val srcIdx = (diploRow[0] as Number).toInt()
                val destIdx = (diploRow[1] as Number).toInt()
                val stateType = (diploRow[2] as Number).toInt()
                val term = (diploRow[3] as Number).toInt()
                val srcId = nationIdxToDbId[srcIdx + 1]
                val destId = nationIdxToDbId[destIdx + 1]
                if (srcId != null && destId != null) {
                    val stateCode = when (stateType) {
                        0 -> "선전포고"
                        1 -> "선전포고"
                        7 -> "불가침"
                        else -> "통상"
                    }
                    diplomacyRepository.save(
                        Diplomacy(
                            worldId = worldId,
                            srcNationId = srcId,
                            destNationId = destId,
                            stateCode = stateCode,
                            term = term.toShort(),
                        )
                    )
                }
            }
        }

        return world
    }

    private fun parseNation(row: List<Any>, worldId: Long): Nation {
        // Format: [name, color, gold, rice, description, tech, type, level, [cities]]
        val typeRaw = row[6].toString()
        val typeCode = if (typeRaw.contains("_")) typeRaw else "che_$typeRaw"
        return Nation(
            worldId = worldId,
            name = row[0] as String,
            color = row[1] as String,
            gold = (row[2] as Number).toInt(),
            rice = (row[3] as Number).toInt(),
            tech = (row[5] as Number).toFloat(),
            typeCode = typeCode,
            level = (row[7] as Number).toShort(),
        )
    }

    private fun parseGeneral(
        row: List<Any?>,
        worldId: Long,
        nationIdxToDbId: Map<Int, Long>,
        nationCityIds: Map<Long, List<Long>>,
        startYear: Int,
        appeared: Boolean,
    ): General {
        // Format: [affinity, name, picture, nationIdx(1-based int), city?,
        //          leadership, strength, intel, politics, charm,
        //          officerLevel, birthYear, deathYear,
        //          personality?, special?, motto?]
        val affinity = (row[0] as? Number)?.toShort() ?: 0
        val name = row[1] as String
        val picture = row[2]?.toString() ?: ""

        // row[3] is a 1-based nation index (int), 0 = no nation
        val nationIdx = (row[3] as? Number)?.toInt() ?: 0
        val nationId = if (nationIdx > 0) nationIdxToDbId[nationIdx] ?: 0L else 0L

        val leadership = (row[5] as Number).toShort()
        val strength = (row[6] as Number).toShort()
        val intel = (row[7] as Number).toShort()
        val politics = (row[8] as Number).toShort()
        val charm = (row[9] as Number).toShort()
        val officerLevel = (row[10] as Number).toShort()
        val bornYear = (row[11] as Number).toShort()
        val deadYear = (row[12] as Number).toShort()
        val personality = row.getOrNull(13)?.toString()
        val special = row.getOrNull(14)?.toString()

        // Determine cityId
        val cityId: Long = if (!appeared) {
            0L // 미등장: no city
        } else if (nationId > 0L) {
            // Assign to a random city of the nation
            val cities = nationCityIds[nationId] ?: emptyList()
            if (cities.isNotEmpty()) cities.random() else 0L
        } else {
            0L // 재야: no city assignment for now
        }

        // NPC state
        val npcState: Short = if (!appeared) {
            75 // 미등장 (확장 장수)
        } else if (nationId == 0L && affinity == 999.toShort()) {
            5 // permanent wanderer
        } else if (nationId == 0L) {
            1 // free NPC (재야)
        } else {
            2 // NPC belonging to nation
        }

        val age = (startYear - bornYear).toShort().coerceAtLeast(20)

        return General(
            worldId = worldId,
            name = name,
            nationId = nationId,
            cityId = cityId,
            affinity = affinity,
            bornYear = bornYear,
            deadYear = deadYear,
            picture = picture,
            leadership = leadership,
            strength = strength,
            intel = intel,
            politics = politics,
            charm = charm,
            officerLevel = officerLevel,
            npcState = npcState,
            age = age,
            startAge = age,
            personalCode = personality ?: "None",
            specialCode = special ?: "None",
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun loadAllScenarios() {
        if (scenarios.isNotEmpty()) return
        val resolver = PathMatchingResourcePatternResolver()
        val resources = resolver.getResources("classpath*:data/scenarios/scenario_*.json")
        for (resource in resources) {
            val filename = resource.filename ?: continue
            val code = filename.removePrefix("scenario_").removeSuffix(".json")
            val data: ScenarioData = objectMapper.readValue(resource.inputStream)
            scenarios[code] = data
        }
    }

    private fun loadDefaults(): ScenarioData {
        val resource = PathMatchingResourcePatternResolver()
            .getResource("classpath*:data/scenarios/default.json")
        return try {
            objectMapper.readValue(resource.inputStream)
        } catch (_: Exception) {
            ScenarioData()
        }
    }
}
