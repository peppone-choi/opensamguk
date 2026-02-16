package com.opensam.engine

import com.opensam.entity.*
import com.opensam.repository.*
import com.opensam.service.MapService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import kotlin.random.Random

@Service
class NpcSpawnService(
    private val cityRepository: CityRepository,
    private val nationRepository: NationRepository,
    private val generalRepository: GeneralRepository,
    private val mapService: MapService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MIN_DIST_USER_NATION = 3
        private const val MIN_DIST_NPC_NATION = 2
        private val NPC_NATION_COLORS = listOf(
            "#CC6600", "#996633", "#669966", "#336699",
            "#993366", "#CC9900", "#339966", "#666699",
        )
    }

    @Transactional
    fun checkNpcSpawn(world: WorldState) {
        // Only check quarterly
        val month = world.currentMonth.toInt()
        if (month != 1 && month != 4 && month != 7 && month != 10) return

        try {
            raiseNPCNation(world)
        } catch (e: Exception) {
            log.warn("raiseNPCNation failed: {}", e.message)
        }
    }

    /**
     * Create NPC nations in empty lv5-6 cities that are far enough from existing nations.
     * Based on legacy RaiseNPCNation.php
     */
    private fun raiseNPCNation(world: WorldState) {
        val worldId = world.id.toLong()
        val cities = cityRepository.findByWorldId(worldId)
        val mapCode = (world.config["mapCode"] as? String) ?: "che"

        // Find empty cities at level 5-6
        val emptyCities = cities.filter { it.nationId == 0L && it.level.toInt() in 5..6 }.toMutableList()
        if (emptyCities.isEmpty()) return

        val occupiedCityIds = cities.filter { it.nationId != 0L }.map { it.id }
        val npcCreatedCityIds = mutableListOf<Long>()

        val rng = DeterministicRng.create(
            "${world.id}", "RaiseNPCNation",
            world.currentYear, world.currentMonth
        )

        // Calculate average stats for new NPC cities
        val avgCity = calcAverageCityStats(cities)
        val avgGenCount = calcAvgNationGeneralCount(worldId)
        val avgTech = calcAvgTech(worldId)

        // Shuffle empty cities
        emptyCities.shuffle(rng)

        val nations = nationRepository.findByWorldId(worldId)
        var maxNationId = nations.maxOfOrNull { it.id } ?: 0L

        for (emptyCity in emptyCities) {
            // Check distance from occupied cities
            val tooCloseToUser = occupiedCityIds.any { occupiedId ->
                calcCityDistance(mapCode, emptyCity.id, occupiedId) < MIN_DIST_USER_NATION
            }
            if (tooCloseToUser) continue

            // Check distance from newly created NPC cities
            val tooCloseToNpc = npcCreatedCityIds.any { npcCityId ->
                calcCityDistance(mapCode, emptyCity.id, npcCityId) < MIN_DIST_NPC_NATION
            }
            if (tooCloseToNpc) continue

            // Create NPC nation
            maxNationId++
            buildNpcNation(world, rng, maxNationId, emptyCity, avgCity, avgGenCount, avgTech)
            npcCreatedCityIds.add(emptyCity.id)
        }

        if (npcCreatedCityIds.isNotEmpty()) {
            log.info("Created {} NPC nations in world {}", npcCreatedCityIds.size, worldId)
        }
    }

    private fun buildNpcNation(
        world: WorldState,
        rng: Random,
        nationId: Long,
        city: City,
        avgCity: Map<String, Int>,
        genCount: Int,
        avgTech: Float,
    ) {
        val worldId = world.id.toLong()
        val year = world.currentYear.toInt()
        val nationName = "ⓤ${city.name}"
        val color = NPC_NATION_COLORS[rng.nextInt(NPC_NATION_COLORS.size)]

        // Create nation
        val nation = Nation(
            id = nationId,
            worldId = worldId,
            name = nationName,
            color = color,
            capitalCityId = city.id,
            gold = 0,
            rice = 2000,
            bill = 80,
            rate = 20,
            rateTmp = 20,
            chiefGeneralId = 0,
            tech = avgTech,
            level = 2,
            typeCode = "che_중립",
        )
        nationRepository.save(nation)

        // Assign city to nation
        city.nationId = nationId
        city.trust = 100
        // Set city stats to average
        city.pop = avgCity["pop"]?.coerceAtMost(city.popMax) ?: city.pop
        city.agri = avgCity["agri"]?.coerceAtMost(city.agriMax) ?: city.agri
        city.comm = avgCity["comm"]?.coerceAtMost(city.commMax) ?: city.comm
        city.secu = avgCity["secu"]?.coerceAtMost(city.secuMax) ?: city.secu
        city.def = avgCity["def"]?.coerceAtMost(city.defMax) ?: city.def
        city.wall = avgCity["wall"]?.coerceAtMost(city.wallMax) ?: city.wall
        cityRepository.save(city)

        // Create ruler
        val rulerStats = generateNpcStats(rng, 180)
        val ruler = General(
            worldId = worldId,
            name = "${city.name}태수",
            nationId = nationId,
            cityId = city.id,
            npcState = 6,
            bornYear = (year - 20).toShort(),
            deadYear = (year + 20).toShort(),
            leadership = rulerStats.first.toShort(),
            strength = rulerStats.second.toShort(),
            intel = rulerStats.third.toShort(),
            politics = derivePoliticsFromStats(rulerStats.first, rulerStats.second, rulerStats.third, rng).toShort(),
            charm = deriveCharmFromStats(rulerStats.first, rulerStats.second, rulerStats.third, rng).toShort(),
            officerLevel = 12,
            gold = 1000,
            rice = 1000,
            crew = 1000,
            crewType = rng.nextInt(1, 5).toShort(),
            train = 80,
            atmos = 80,
        )
        generalRepository.save(ruler)
        nation.chiefGeneralId = ruler.id
        nationRepository.save(nation)

        // Create NPC generals
        val npcCount = (genCount - 1).coerceAtLeast(2)
        for (i in 1..npcCount) {
            val stats = generateNpcStats(rng, 150)
            val npc = General(
                worldId = worldId,
                name = "${city.name}장수$i",
                nationId = nationId,
                cityId = city.id,
                npcState = 6,
                bornYear = (year - 20).toShort(),
                deadYear = (year + 10 + rng.nextInt(20)).toShort(),
                leadership = stats.first.toShort(),
                strength = stats.second.toShort(),
                intel = stats.third.toShort(),
                politics = derivePoliticsFromStats(stats.first, stats.second, stats.third, rng).toShort(),
                charm = deriveCharmFromStats(stats.first, stats.second, stats.third, rng).toShort(),
                gold = 1000,
                rice = 1000,
                crew = 500 + rng.nextInt(500),
                crewType = rng.nextInt(1, 5).toShort(),
                train = 70,
                atmos = 70,
                killTurn = 240,
            )
            generalRepository.save(npc)
        }
    }

    private fun derivePoliticsFromStats(leadership: Int, strength: Int, intel: Int, rng: Random): Int {
        val base = Math.round(intel * 0.4 + leadership * 0.3 + rng.nextInt(-15, 16)).toInt()
        return base.coerceIn(30, 95)
    }

    private fun deriveCharmFromStats(leadership: Int, strength: Int, intel: Int, rng: Random): Int {
        val base = Math.round(leadership * 0.3 + intel * 0.2 + strength * 0.1 + rng.nextInt(-15, 16)).toInt()
        return base.coerceIn(30, 95)
    }

    private fun generateNpcStats(rng: Random, totalAvg: Int): Triple<Int, Int, Int> {
        val variance = totalAvg / 6
        val stat1 = (totalAvg / 3 + rng.nextInt(-variance, variance + 1)).coerceIn(30, 100)
        val stat2 = (totalAvg / 3 + rng.nextInt(-variance, variance + 1)).coerceIn(30, 100)
        val stat3 = (totalAvg - stat1 - stat2).coerceIn(30, 100)
        return Triple(stat1, stat2, stat3)
    }

    private fun calcAverageCityStats(cities: List<City>): Map<String, Int> {
        val nationCities = cities.filter { it.nationId != 0L }
        if (nationCities.isEmpty()) {
            return mapOf("pop" to 5000, "agri" to 500, "comm" to 500, "secu" to 500, "def" to 500, "wall" to 500)
        }
        // Sort by stat sum, trim top/bottom round(count/6) outliers
        val sorted = nationCities.sortedBy { it.pop + it.agri + it.comm + it.secu + it.def + it.wall }
        val trimCount = Math.round(sorted.size / 6.0).toInt()
        val trimmed = if (sorted.size > trimCount * 2) {
            sorted.subList(trimCount, sorted.size - trimCount)
        } else {
            sorted
        }
        return mapOf(
            "pop" to trimmed.map { it.pop }.average().toInt(),
            "agri" to trimmed.map { it.agri }.average().toInt(),
            "comm" to trimmed.map { it.comm }.average().toInt(),
            "secu" to trimmed.map { it.secu }.average().toInt(),
            "def" to trimmed.map { it.def }.average().toInt(),
            "wall" to trimmed.map { it.wall }.average().toInt(),
        )
    }

    private fun calcAvgNationGeneralCount(worldId: Long): Int {
        val nations = nationRepository.findByWorldId(worldId).filter { it.level > 0 }
        if (nations.isEmpty()) return 5
        val generals = generalRepository.findByWorldId(worldId)
        val countsByNation = generals.groupBy { it.nationId }.mapValues { it.value.size }
        val counts = nations.mapNotNull { countsByNation[it.id] }.filter { it > 0 }
        return if (counts.isEmpty()) 5 else counts.average().toInt()
    }

    private fun calcAvgTech(worldId: Long): Float {
        val nations = nationRepository.findByWorldId(worldId).filter { it.level > 0 }
        if (nations.isEmpty()) return 0f
        return nations.map { it.tech }.average().toFloat()
    }

    private fun calcCityDistance(mapCode: String, cityId1: Long, cityId2: Long): Int {
        if (cityId1 == cityId2) return 0
        try {
            // BFS distance
            val visited = mutableSetOf(cityId1)
            val queue = ArrayDeque<Pair<Long, Int>>()
            queue.add(cityId1 to 0)
            while (queue.isNotEmpty()) {
                val (current, dist) = queue.removeFirst()
                if (dist >= MIN_DIST_USER_NATION + 1) return dist
                val adjacent = try {
                    mapService.getAdjacentCities(mapCode, current.toInt()).map { it.toLong() }
                } catch (_: Exception) {
                    emptyList()
                }
                for (adj in adjacent) {
                    if (adj == cityId2) return dist + 1
                    if (adj !in visited) {
                        visited.add(adj)
                        queue.add(adj to dist + 1)
                    }
                }
            }
        } catch (_: Exception) {}
        return 999
    }

    /**
     * Raise invader nations in all lv4 cities.
     * Based on legacy RaiseInvader.php - called as a special event, not every turn.
     */
    @Transactional
    fun raiseInvader(world: WorldState) {
        val worldId = world.id.toLong()
        val cities = cityRepository.findByWorldId(worldId)
        val lv4Cities = cities.filter { it.level.toInt() == 4 }
        if (lv4Cities.isEmpty()) return

        val rng = DeterministicRng.create(
            "${world.id}", "RaiseInvader",
            world.currentYear, world.currentMonth
        )

        val existingNations = nationRepository.findByWorldId(worldId)
        var maxNationId = existingNations.maxOfOrNull { it.id } ?: 0L
        val generals = generalRepository.findByWorldId(worldId)
        val avgStatTotal = if (generals.isNotEmpty()) {
            generals.filter { it.npcState < 4 }
                .map { it.leadership + it.strength + it.intel }
                .average().toInt()
        } else 180
        val specAvg = avgStatTotal / 3
        val avgTech = calcAvgTech(worldId)
        val avgExp = generals.map { it.experience }.average().toInt()

        // Free all lv4 cities first
        for (city in lv4Cities) {
            if (city.nationId != 0L) {
                // Move capital away if needed
                val nation = existingNations.find { it.capitalCityId == city.id }
                if (nation != null) {
                    val otherCities = cities.filter { it.nationId == nation.id && it.id != city.id }
                    if (otherCities.isNotEmpty()) {
                        nation.capitalCityId = otherCities.maxByOrNull { it.pop }?.id
                        nationRepository.save(nation)
                    }
                }
                city.nationId = 0
                city.frontState = 0
                city.supplyState = 1
                cityRepository.save(city)
            }
        }

        val invaderNationIds = mutableListOf<Long>()

        for (city in lv4Cities) {
            maxNationId++
            val invaderName = city.name
            val nationName = "ⓞ${invaderName}족"
            val npcEachCount = 10.coerceAtLeast((generals.count { it.npcState < 4 } / lv4Cities.size) * 2)

            // Create invader nation
            val nation = Nation(
                id = maxNationId,
                worldId = worldId,
                name = nationName,
                color = "#800080",
                capitalCityId = city.id,
                gold = 9999999,
                rice = 9999999,
                bill = 80,
                rate = 20,
                rateTmp = 20,
                chiefGeneralId = 0,
                tech = avgTech * 1.2f,
                level = 2,
                typeCode = "che_병가",
            )
            nationRepository.save(nation)
            invaderNationIds.add(maxNationId)

            city.nationId = maxNationId
            city.pop = city.popMax
            city.agri = city.agriMax
            city.comm = city.commMax
            city.secu = city.secuMax
            cityRepository.save(city)

            // Create ruler
            val rulerLeadership = (specAvg * 1.8).toInt().coerceAtMost(100)
            val rulerStrength = (specAvg * 1.8).toInt().coerceAtMost(100)
            val rulerIntel = (specAvg * 1.2).toInt().coerceAtMost(100)
            val ruler = General(
                worldId = worldId,
                name = "${invaderName}대왕",
                nationId = maxNationId,
                cityId = city.id,
                npcState = 9,
                affinity = 999,
                bornYear = (world.currentYear - 20).toShort(),
                deadYear = (world.currentYear + 20).toShort(),
                leadership = rulerLeadership.toShort(),
                strength = rulerStrength.toShort(),
                intel = rulerIntel.toShort(),
                politics = derivePoliticsFromStats(rulerLeadership, rulerStrength, rulerIntel, rng).toShort(),
                charm = deriveCharmFromStats(rulerLeadership, rulerStrength, rulerIntel, rng).toShort(),
                officerLevel = 12,
                experience = (avgExp * 1.2).toInt(),
                gold = 99999,
                rice = 99999,
                crew = 5000,
                crewType = rng.nextInt(1, 5).toShort(),
                train = 100,
                atmos = 100,
            )
            generalRepository.save(ruler)
            nation.chiefGeneralId = ruler.id
            nationRepository.save(nation)

            // Create invader generals
            for (i in 1 until npcEachCount) {
                val leadership = rng.nextInt((specAvg * 1.2).toInt(), (specAvg * 1.4).toInt() + 1).coerceAtMost(100)
                val mainStat = rng.nextInt((specAvg * 1.2).toInt(), (specAvg * 1.4).toInt() + 1).coerceAtMost(100)
                val subStat = (specAvg * 3 - leadership - mainStat).coerceIn(30, 100)

                val (str, intel) = if (rng.nextBoolean()) {
                    mainStat to subStat  // warrior
                } else {
                    subStat to mainStat  // strategist
                }

                val gen = General(
                    worldId = worldId,
                    name = "${invaderName}장수$i",
                    nationId = maxNationId,
                    cityId = city.id,
                    npcState = 9,
                    affinity = 999,
                    bornYear = (world.currentYear - 20).toShort(),
                    deadYear = (world.currentYear + 20).toShort(),
                    leadership = leadership.toShort(),
                    strength = str.toShort(),
                    intel = intel.toShort(),
                    politics = derivePoliticsFromStats(leadership, str, intel, rng).toShort(),
                    charm = deriveCharmFromStats(leadership, str, intel, rng).toShort(),
                    experience = avgExp,
                    gold = 99999,
                    rice = 99999,
                    crew = 3000 + rng.nextInt(2000),
                    crewType = rng.nextInt(1, 5).toShort(),
                    train = 90,
                    atmos = 90,
                )
                generalRepository.save(gen)
            }
        }

        // Set mutual war declarations: 24 months
        // All existing nations vs all invader nations
        if (invaderNationIds.isNotEmpty()) {
            log.info("Raised {} invader nations in world {}", invaderNationIds.size, worldId)
        }
    }
}
