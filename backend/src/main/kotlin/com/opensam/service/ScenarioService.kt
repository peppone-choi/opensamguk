package com.opensam.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.opensam.entity.*
import com.opensam.model.ScenarioData
import com.opensam.model.ScenarioInfo
import com.opensam.repository.*
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class ScenarioService(
    private val objectMapper: ObjectMapper,
    private val worldStateRepository: WorldStateRepository,
    private val nationRepository: NationRepository,
    private val cityRepository: CityRepository,
    private val generalRepository: GeneralRepository,
    private val diplomacyRepository: DiplomacyRepository,
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
    fun initializeWorld(scenarioCode: String, tickSeconds: Int = 300): WorldState {
        val scenario = getScenario(scenarioCode)
        val defaults = loadDefaults()

        // Create world
        val world = worldStateRepository.save(
            WorldState(
                scenarioCode = scenarioCode,
                currentYear = scenario.startYear.toShort(),
                currentMonth = 1,
                tickSeconds = tickSeconds,
            )
        )
        val worldId = world.id.toLong()

        // Create nations
        val nationNameToId = mutableMapOf<String, Long>()
        for (nationRow in scenario.nation) {
            val nation = parseNation(nationRow, worldId)
            val saved = nationRepository.save(nation)
            nationNameToId[saved.name] = saved.id
        }

        // Create cities from map data (simplified - cities are loaded from map service)
        // For now, just create cities referenced by nations

        // Create generals
        val stat = scenario.stat ?: defaults.stat
        for (generalRow in scenario.general) {
            val general = parseGeneral(generalRow, worldId, nationNameToId, scenario.startYear)
            generalRepository.save(general)
        }
        for (generalRow in scenario.generalEx) {
            val general = parseGeneral(generalRow, worldId, nationNameToId, scenario.startYear)
            generalRepository.save(general)
        }

        // Create diplomacy
        val nationIds = nationNameToId.values.toList()
        for (diploRow in scenario.diplomacy) {
            if (diploRow.size >= 4) {
                val srcIdx = (diploRow[0] as Number).toInt()
                val destIdx = (diploRow[1] as Number).toInt()
                val stateType = (diploRow[2] as Number).toInt()
                val term = (diploRow[3] as Number).toInt()
                if (srcIdx < nationIds.size && destIdx < nationIds.size) {
                    val stateCode = when (stateType) {
                        0 -> "war"
                        1 -> "declaration"
                        7 -> "nonaggression"
                        else -> "normal"
                    }
                    diplomacyRepository.save(
                        Diplomacy(
                            worldId = worldId,
                            srcNationId = nationIds[srcIdx],
                            destNationId = nationIds[destIdx],
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
        return Nation(
            worldId = worldId,
            name = row[0] as String,
            color = row[1] as String,
            gold = (row[2] as Number).toInt(),
            rice = (row[3] as Number).toInt(),
            tech = (row[5] as Number).toFloat(),
            typeCode = "che_${row[6]}",
            level = (row[7] as Number).toShort(),
        )
    }

    private fun parseGeneral(row: List<Any?>, worldId: Long, nationNameToId: Map<String, Long>, startYear: Int): General {
        // Format: [affinity, name, picture, nationName?, city?,
        //          leadership, strength, intel, politics, charm,
        //          officerLevel, birthYear, deathYear,
        //          personality?, special?, motto?]
        val affinity = (row[0] as? Number)?.toShort() ?: 0
        val name = row[1] as String
        val picture = row[2]?.toString() ?: ""
        val nationName = row[3]?.toString()
        val nationId = if (nationName != null && nationName.isNotEmpty()) {
            nationNameToId[nationName] ?: 0L
        } else 0L

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

        val npcState: Short = if (nationId == 0L && affinity == 999.toShort()) 5 // permanent wanderer
        else if (nationId == 0L) 1 // free NPC
        else 0 // belongs to nation

        val age = (startYear - bornYear).toShort().coerceAtLeast(20)

        return General(
            worldId = worldId,
            name = name,
            nationId = nationId,
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
        val resources = resolver.getResources("classpath:data/scenarios/scenario_*.json")
        for (resource in resources) {
            val filename = resource.filename ?: continue
            val code = filename.removePrefix("scenario_").removeSuffix(".json")
            val data: ScenarioData = objectMapper.readValue(resource.inputStream)
            scenarios[code] = data
        }
    }

    private fun loadDefaults(): ScenarioData {
        val resource = PathMatchingResourcePatternResolver()
            .getResource("classpath:data/scenarios/default.json")
        return try {
            objectMapper.readValue(resource.inputStream)
        } catch (_: Exception) {
            ScenarioData()
        }
    }
}
