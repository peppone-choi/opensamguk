package com.opensam.service

import com.opensam.entity.WorldHistory
import com.opensam.entity.WorldState
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository
import com.opensam.repository.WorldHistoryRepository
import com.opensam.repository.WorldStateRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class WorldService(
    private val worldStateRepository: WorldStateRepository,
    private val nationRepository: NationRepository,
    private val cityRepository: CityRepository,
    private val generalRepository: GeneralRepository,
    private val worldHistoryRepository: WorldHistoryRepository,
) {
    private val log = LoggerFactory.getLogger(WorldService::class.java)

    companion object {
        // Game phases (legacy GameConst parity)
        const val PHASE_OPENING = "opening"      // 초반 제한 기간
        const val PHASE_NORMAL = "normal"         // 일반 진행
        const val PHASE_ENDING = "ending"         // 통일 임박
        const val PHASE_FINISHED = "finished"     // 게임 종료

        // Seasons by month (삼국지 legacy)
        val SEASON_BY_MONTH = mapOf(
            1 to "봄", 2 to "봄", 3 to "봄",
            4 to "여름", 5 to "여름", 6 to "여름",
            7 to "가을", 8 to "가을", 9 to "가을",
            10 to "겨울", 11 to "겨울", 12 to "겨울",
        )

        // Legacy: opening part duration in years
        const val OPENING_PART_YEARS = 3

        // Legacy defaults from GameConstBase
        const val DEFAULT_MAX_GENERAL = 500
        const val DEFAULT_MAX_NATION = 55
        const val DEFAULT_MAX_GENIUS = 5
        const val DEFAULT_START_YEAR = 180
        const val DEFAULT_GOLD = 1000
        const val DEFAULT_RICE = 1000
        const val MAX_DED_LEVEL = 30
        const val MAX_TECH_LEVEL = 12
        const val MAX_BETRAY_CNT = 9
        const val MAX_TURN = 30
        const val MAX_CHIEF_TURN = 12
        const val DEX_LIMIT = 1000000
        const val SABOTAGE_DEFAULT_PROB = 0.35
        const val EXCHANGE_FEE = 0.01
        const val MIN_RECRUIT_POP = 30000
        const val TECH_LEVEL_INC_YEAR = 5
        const val INITIAL_ALLOWED_TECH_LEVEL = 1
    }

    // ── Basic CRUD ──

    fun listWorlds(): List<WorldState> {
        return worldStateRepository.findAll()
    }

    fun getWorld(id: Short): WorldState? {
        return worldStateRepository.findById(id).orElse(null)
    }

    fun save(world: WorldState): WorldState {
        world.updatedAt = OffsetDateTime.now()
        return worldStateRepository.save(world)
    }

    fun deleteWorld(id: Short) {
        worldStateRepository.deleteById(id)
    }

    // ── Game Phase / Season Tracking ──

    /**
     * Get the current game phase based on world state.
     * Legacy parity: opening period, normal play, ending (single nation remaining).
     */
    fun getGamePhase(world: WorldState): String {
        // Check if game is explicitly finished
        val finished = world.config["finished"] as? Boolean ?: false
        if (finished) return PHASE_FINISHED

        // Check if locked (admin paused)
        val locked = world.config["locked"] as? Boolean ?: false
        if (locked) return world.config["phase"] as? String ?: PHASE_NORMAL

        val startYear = (world.config["startYear"] as? Number)?.toInt() ?: world.currentYear.toInt()
        val yearsElapsed = world.currentYear.toInt() - startYear

        // Opening period
        if (yearsElapsed < OPENING_PART_YEARS) return PHASE_OPENING

        // Check remaining nations for ending
        val nationCount = nationRepository.findByWorldId(world.id.toLong()).count { it.level > 0 }
        if (nationCount <= 1) return PHASE_ENDING

        return PHASE_NORMAL
    }

    /**
     * Get the current season name.
     */
    fun getCurrentSeason(world: WorldState): String {
        return SEASON_BY_MONTH[world.currentMonth.toInt()] ?: "봄"
    }

    /**
     * Get the allowed tech level based on elapsed years.
     * Legacy: initialAllowedTechLevel + (yearsElapsed / techLevelIncYear)
     */
    fun getAllowedTechLevel(world: WorldState): Int {
        val startYear = (world.config["startYear"] as? Number)?.toInt() ?: world.currentYear.toInt()
        val yearsElapsed = (world.currentYear.toInt() - startYear).coerceAtLeast(0)
        return (INITIAL_ALLOWED_TECH_LEVEL + yearsElapsed / TECH_LEVEL_INC_YEAR).coerceAtMost(MAX_TECH_LEVEL)
    }

    // ── World Configuration Management ──

    /**
     * Get a configuration value with a typed default.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getConfig(world: WorldState, key: String, default: T): T {
        return (world.config[key] as? T) ?: default
    }

    /**
     * Update a configuration value.
     */
    @Transactional
    fun setConfig(worldId: Short, key: String, value: Any): Boolean {
        val world = getWorld(worldId) ?: return false
        world.config[key] = value
        save(world)
        return true
    }

    /**
     * Get the map code for the world.
     */
    fun getMapCode(world: WorldState): String {
        return (world.config["mapCode"] as? String) ?: "che"
    }

    /**
     * Get the unit set for the world.
     */
    fun getUnitSet(world: WorldState): String {
        return (world.config["unitSet"] as? String) ?: "che"
    }

    // ── World-Level Computed Properties ──

    /**
     * Get total population across all cities in the world.
     */
    fun getTotalPopulation(worldId: Long): Long {
        return cityRepository.findByWorldId(worldId).sumOf { it.pop.toLong() }
    }

    /**
     * Get count of active nations (level > 0).
     */
    fun getActiveNationCount(worldId: Long): Int {
        return nationRepository.findByWorldId(worldId).count { it.level > 0 }
    }

    /**
     * Get count of active generals (not in pool, npcState != 5).
     */
    fun getActiveGeneralCount(worldId: Long): Int {
        return generalRepository.findByWorldId(worldId).count { it.npcState.toInt() != 5 }
    }

    /**
     * Get count of human players (userId != null).
     */
    fun getHumanPlayerCount(worldId: Long): Int {
        return generalRepository.findByWorldId(worldId).count { it.userId != null }
    }

    /**
     * Check if any wars are active in the world.
     * A nation is at war if warState > 0.
     */
    fun isAtWar(worldId: Long): Boolean {
        return nationRepository.findByWorldId(worldId).any { it.warState > 0 }
    }

    /**
     * Get total cities and cities per nation.
     */
    fun getCityDistribution(worldId: Long): Map<Long, Int> {
        return cityRepository.findByWorldId(worldId)
            .filter { it.nationId != 0L }
            .groupBy { it.nationId }
            .mapValues { it.value.size }
    }

    // ── World State Snapshot ──

    /**
     * Capture a full world state snapshot for history/replay.
     * Saves a WorldHistory entry with type "snapshot" containing the entire game state.
     */
    @Transactional
    fun captureSnapshot(world: WorldState): WorldHistory {
        val worldId = world.id.toLong()
        val nations = nationRepository.findByWorldId(worldId)
        val cities = cityRepository.findByWorldId(worldId)
        val generals = generalRepository.findByWorldId(worldId)

        val nationSummaries = nations.filter { it.level > 0 }.map { nation ->
            val nationCities = cities.filter { it.nationId == nation.id }
            val nationGenerals = generals.filter { it.nationId == nation.id && it.npcState.toInt() != 5 }
            mapOf(
                "id" to nation.id,
                "name" to nation.name,
                "color" to nation.color,
                "level" to nation.level.toInt(),
                "power" to nation.power,
                "gold" to nation.gold,
                "rice" to nation.rice,
                "tech" to nation.tech,
                "cityCount" to nationCities.size,
                "generalCount" to nationGenerals.size,
                "population" to nationCities.sumOf { it.pop.toLong() },
                "warState" to nation.warState.toInt(),
                "capitalCityId" to nation.capitalCityId,
            )
        }

        val citySummaries = cities.map { city ->
            mapOf(
                "id" to city.id,
                "name" to city.name,
                "nationId" to city.nationId,
                "level" to city.level.toInt(),
                "pop" to city.pop,
                "region" to city.region.toInt(),
                "supplyState" to city.supplyState.toInt(),
            )
        }

        val payload = mutableMapOf<String, Any>(
            "worldId" to worldId,
            "year" to world.currentYear.toInt(),
            "month" to world.currentMonth.toInt(),
            "phase" to getGamePhase(world),
            "season" to getCurrentSeason(world),
            "totalPopulation" to cities.sumOf { it.pop.toLong() },
            "activeNations" to nations.count { it.level > 0 },
            "activeGenerals" to generals.count { it.npcState.toInt() != 5 },
            "humanPlayers" to generals.count { it.userId != null },
            "atWar" to nations.any { it.warState > 0 },
            "nations" to nationSummaries,
            "cities" to citySummaries,
        )

        val history = WorldHistory(
            worldId = worldId,
            year = world.currentYear,
            month = world.currentMonth,
            eventType = "snapshot",
            payload = payload,
        )

        val saved = worldHistoryRepository.save(history)
        log.info("[World {}] Snapshot captured at {}/{}, id={}", worldId, world.currentYear, world.currentMonth, saved.id)
        return saved
    }

    /**
     * Get all snapshots for a world, ordered by creation time.
     */
    fun getSnapshots(worldId: Long): List<WorldHistory> {
        return worldHistoryRepository.findByWorldIdAndEventType(worldId, "snapshot")
    }

    /**
     * Get the latest snapshot for a world.
     */
    fun getLatestSnapshot(worldId: Long): WorldHistory? {
        return worldHistoryRepository.findByWorldIdAndEventType(worldId, "snapshot")
            .maxByOrNull { it.createdAt }
    }

    // ── World Summary (for API responses) ──

    /**
     * Get a comprehensive world summary.
     */
    fun getWorldSummary(worldId: Short): Map<String, Any>? {
        val world = getWorld(worldId) ?: return null
        val wid = world.id.toLong()
        return mapOf(
            "id" to world.id,
            "name" to world.name,
            "scenarioCode" to world.scenarioCode,
            "currentYear" to world.currentYear.toInt(),
            "currentMonth" to world.currentMonth.toInt(),
            "season" to getCurrentSeason(world),
            "phase" to getGamePhase(world),
            "tickSeconds" to world.tickSeconds,
            "realtimeMode" to world.realtimeMode,
            "totalPopulation" to getTotalPopulation(wid),
            "activeNations" to getActiveNationCount(wid),
            "activeGenerals" to getActiveGeneralCount(wid),
            "humanPlayers" to getHumanPlayerCount(wid),
            "atWar" to isAtWar(wid),
            "allowedTechLevel" to getAllowedTechLevel(world),
            "mapCode" to getMapCode(world),
        )
    }
}
