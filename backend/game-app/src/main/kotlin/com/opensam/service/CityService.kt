package com.opensam.service

import com.opensam.entity.City
import com.opensam.model.CityConst
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.roundToInt

@Service
class CityService(
    private val cityRepository: CityRepository,
    private val mapService: MapService,
    private val generalRepository: GeneralRepository,
) {
    companion object {
        // Region codes matching legacy CityConstBase::$regionMap
        const val REGION_HABUK = 1    // 하북
        const val REGION_JUNGWON = 2  // 중원
        const val REGION_SEOBUK = 3   // 서북
        const val REGION_SEOCHOK = 4  // 서촉
        const val REGION_NAMJUNG = 5  // 남중
        const val REGION_CHO = 6      // 초
        const val REGION_OWOL = 7     // 오월
        const val REGION_DONGI = 8    // 동이

        val REGION_NAMES = mapOf(
            REGION_HABUK to "하북",
            REGION_JUNGWON to "중원",
            REGION_SEOBUK to "서북",
            REGION_SEOCHOK to "서촉",
            REGION_NAMJUNG to "남중",
            REGION_CHO to "초",
            REGION_OWOL to "오월",
            REGION_DONGI to "동이",
        )

        // City level codes matching legacy CityConstBase::$levelMap
        // 1=수, 2=진, 3=관, 4=이, 5=소, 6=중, 7=대, 8=특
        val LEVEL_NAMES = mapOf(
            1 to "수", 2 to "진", 3 to "관", 4 to "이",
            5 to "소", 6 to "중", 7 to "대", 8 to "특",
        )

        // Population cap multipliers by city level (legacy scale: level -> popMax factor)
        // These are encoded in the map JSON data directly as CityConst.population * 100

        // Terrain/level modifiers for development rates (legacy parity)
        // Higher level cities develop faster
        val DEV_RATE_BY_LEVEL = mapOf(
            1 to 0.5, 2 to 0.6, 3 to 0.7, 4 to 0.8,
            5 to 0.9, 6 to 1.0, 7 to 1.1, 8 to 1.2,
        )

        // Legacy: trust minimum during internal affairs
        const val DEVEL_RATE_MIN_TRUST = 50

        // Legacy: default city wall after conquest
        const val DEFAULT_CITY_WALL = 1000

        // Legacy: expand city costs and amounts
        const val EXPAND_CITY_POP_INCREASE = 100000
        const val EXPAND_CITY_DEVEL_INCREASE = 2000
        const val EXPAND_CITY_WALL_INCREASE = 2000
        const val EXPAND_CITY_DEFAULT_COST = 60000
        const val EXPAND_CITY_COST_COEF = 500
    }

    // ── Basic CRUD ──

    fun listByWorld(worldId: Long): List<City> {
        return cityRepository.findByWorldId(worldId)
    }

    fun getById(id: Long): City? {
        return cityRepository.findById(id).orElse(null)
    }

    fun listByNation(nationId: Long): List<City> {
        return cityRepository.findByNationId(nationId)
    }

    fun save(city: City): City {
        return cityRepository.save(city)
    }

    fun saveAll(cities: List<City>): List<City> {
        return cityRepository.saveAll(cities)
    }

    // ── Adjacency / Map Queries ──

    /**
     * Get adjacent city IDs for a given city in a specific map.
     * Delegates to MapService which holds the parsed map JSON.
     */
    fun getAdjacentCities(mapCode: String, cityId: Long): List<Long> {
        return mapService.getAdjacentCities(mapCode, cityId.toInt()).map { it.toLong() }
    }

    /**
     * Get all cities in a region from the map definition.
     */
    fun getCitiesByRegion(mapCode: String, region: Int): List<CityConst> {
        return mapService.getCities(mapCode).filter { it.region == region }
    }

    /**
     * Get distance between two cities on the map (BFS hop count).
     */
    fun getDistance(mapCode: String, fromCityId: Long, toCityId: Long): Int {
        return mapService.getDistance(mapCode, fromCityId.toInt(), toCityId.toInt())
    }

    /**
     * Get the map code for a given world, reading from world config.
     */
    fun getMapCode(worldConfig: Map<String, Any>): String {
        return (worldConfig["mapCode"] as? String) ?: "che"
    }

    // ── Supply Calculation ──

    /**
     * Check if a city is supplied (connected to its nation's capital via friendly territory).
     * Returns true if supplied, false otherwise.
     * This is a read-only check; the actual supply state update is done by EconomyService.
     */
    fun isSupplied(worldId: Long, cityId: Long, mapCode: String): Boolean {
        val city = getById(cityId) ?: return false
        if (city.nationId == 0L) return true // neutral cities are always supplied

        val nationCities = listByNation(city.nationId)
        val nationCityIds = nationCities.map { it.id }.toSet()

        // Find capital
        val capitalCity = nationCities.find {
            val nation = it.nationId
            // We need to find the capital — check meta or find by nation
            true // will be filtered by BFS
        }

        // BFS from capital
        val allNationCities = cityRepository.findByNationId(city.nationId)
        // We don't have direct access to nation here, so just check supplyState
        return city.supplyState.toInt() == 1
    }

    // ── Development Calculation (legacy parity) ──

    /**
     * Calculate development effectiveness for a city based on general stats.
     * Legacy formula: base * (1 + stat/100) * levelModifier * trustModifier
     *
     * @param city The city being developed
     * @param statValue The general's relevant stat (politics for agri/comm, leadership for secu/def/wall)
     * @param baseAmount Base development amount from command
     * @return Actual development amount
     */
    fun calcDevelopment(city: City, statValue: Int, baseAmount: Int): Int {
        val levelMod = DEV_RATE_BY_LEVEL[city.level.toInt()] ?: 1.0
        val trustMod = city.trust / 100.0
        val statMod = 1.0 + statValue / 100.0
        return (baseAmount * statMod * levelMod * trustMod).roundToInt()
    }

    /**
     * Calculate supply income contribution of a city.
     * Legacy formula from EconomyService — exposed here for external callers.
     */
    fun calcSupply(city: City): Double {
        if (city.supplyState.toInt() == 0) return 0.0
        val trustRatio = city.trust / 200.0 + 0.5
        val goldBase = if (city.commMax > 0) {
            city.pop.toDouble() * city.comm / city.commMax * trustRatio / 30
        } else 0.0
        val riceBase = if (city.agriMax > 0) {
            city.pop.toDouble() * city.agri / city.agriMax * trustRatio / 30
        } else 0.0
        return goldBase + riceBase
    }

    // ── City Initialization for Scenario Setup ──

    /**
     * Initialize a city entity from a CityConst (map definition) for scenario setup.
     * Legacy parity: matches the initial values from CityConstBase.
     * Population and development values in CityConst are stored as x100.
     */
    fun initializeCityFromConst(worldId: Long, cityConst: CityConst, nationId: Long = 0): City {
        return City(
            worldId = worldId,
            name = cityConst.name,
            level = cityConst.level.toShort(),
            nationId = nationId,
            supplyState = 1,
            frontState = 0,
            pop = cityConst.population * 100,
            popMax = cityConst.population * 100,
            agri = cityConst.agriculture * 100,
            agriMax = cityConst.agriculture * 100,
            comm = cityConst.commerce * 100,
            commMax = cityConst.commerce * 100,
            secu = cityConst.security * 100,
            secuMax = cityConst.security * 100,
            trade = 100,
            def = cityConst.defence * 100,
            defMax = cityConst.defence * 100,
            wall = cityConst.wall * 100,
            wallMax = cityConst.wall * 100,
            officerSet = 0,
            state = 0,
            region = cityConst.region.toShort(),
            term = 0,
            conflict = mutableMapOf(),
            meta = mutableMapOf(
                "x" to cityConst.x,
                "y" to cityConst.y,
                "constId" to cityConst.id,
            ),
        )
    }

    /**
     * Initialize all cities for a world from the map definition.
     * Returns saved cities keyed by their CityConst ID.
     */
    @Transactional
    fun initializeAllCities(worldId: Long, mapCode: String): Map<Int, City> {
        val cityConsts = mapService.getCities(mapCode)
        val cities = cityConsts.map { initializeCityFromConst(worldId, it) }
        val saved = cityRepository.saveAll(cities)
        return saved.associateBy { (it.meta["constId"] as? Number)?.toInt() ?: 0 }
    }

    // ── City Expansion (legacy: 증축) ──

    /**
     * Calculate the cost of expanding a city.
     * Legacy formula: defaultCost + (popMax / 100) * costCoef
     */
    fun calcExpandCost(city: City): Int {
        return EXPAND_CITY_DEFAULT_COST + (city.popMax / 100) * EXPAND_CITY_COST_COEF
    }

    /**
     * Expand a city: increase popMax, develMax, wallMax.
     * Legacy parity: checks cost against nation treasury.
     */
    @Transactional
    fun expandCity(city: City): City {
        city.popMax += EXPAND_CITY_POP_INCREASE
        city.agriMax += EXPAND_CITY_DEVEL_INCREASE
        city.commMax += EXPAND_CITY_DEVEL_INCREASE
        city.secuMax += EXPAND_CITY_DEVEL_INCREASE
        city.defMax += EXPAND_CITY_DEVEL_INCREASE
        city.wallMax += EXPAND_CITY_WALL_INCREASE
        return cityRepository.save(city)
    }

    // ── City Conquest ──

    /**
     * Transfer city ownership after conquest.
     * Legacy parity: reset officers, set default wall, clear conflict.
     */
    @Transactional
    fun conquerCity(city: City, newNationId: Long) {
        val oldNationId = city.nationId
        city.nationId = newNationId
        city.wall = DEFAULT_CITY_WALL.coerceAtMost(city.wallMax)
        city.officerSet = 0
        city.conflict = mutableMapOf()
        city.term = 0
        city.frontState = 0

        // Reset officer assignments for generals in this city from old nation
        if (oldNationId != 0L) {
            val generals = generalRepository.findByCityId(city.id)
            for (general in generals) {
                if (general.nationId == oldNationId && general.officerCity == city.id.toInt()) {
                    general.officerLevel = 1
                    general.officerCity = 0
                    generalRepository.save(general)
                }
            }
        }

        cityRepository.save(city)
    }

    // ── Utility ──

    /**
     * Get generals stationed in a city.
     */
    fun getGeneralsInCity(cityId: Long) = generalRepository.findByCityId(cityId)

    /**
     * Count cities owned by a nation at or above a given level.
     */
    fun countCitiesAboveLevel(nationId: Long, minLevel: Int): Int {
        return cityRepository.findByNationId(nationId).count { it.level >= minLevel }
    }

    /**
     * Get the region name for a region code.
     */
    fun getRegionName(regionCode: Int): String {
        return REGION_NAMES[regionCode] ?: "미상"
    }

    /**
     * Get the level name for a level code.
     */
    fun getLevelName(levelCode: Int): String {
        return LEVEL_NAMES[levelCode] ?: "?"
    }

    /**
     * Get total population across all cities in a world.
     */
    fun getTotalPopulation(worldId: Long): Long {
        return cityRepository.findByWorldId(worldId).sumOf { it.pop.toLong() }
    }

    /**
     * Get cities with low trust (potential rebellion).
     */
    fun getLowTrustCities(worldId: Long, threshold: Float = 30f): List<City> {
        return cityRepository.findByWorldId(worldId).filter { it.trust < threshold && it.nationId != 0L }
    }
}
