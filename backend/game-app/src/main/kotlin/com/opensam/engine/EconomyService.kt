package com.opensam.engine

import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import com.opensam.entity.WorldState
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import com.opensam.engine.modifier.IncomeContext
import com.opensam.engine.modifier.NationTypeModifiers
import com.opensam.repository.NationRepository
import com.opensam.service.MapService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

@Service
class EconomyService(
    private val cityRepository: CityRepository,
    private val nationRepository: NationRepository,
    private val generalRepository: GeneralRepository,
    private val mapService: MapService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val BASE_GOLD = 0
        private const val BASE_RICE = 2000
        private const val BASE_POP_INCREASE = 5000
        private const val MAX_DED_LEVEL = 30

        // nationLevelByCityCnt[level] = minimum lv4+ city count
        private val NATION_LEVEL_THRESHOLDS = intArrayOf(0, 1, 2, 5, 8, 11, 16, 21)
    }

    @Transactional
    fun processMonthly(world: WorldState) {
        val cities = cityRepository.findByWorldId(world.id.toLong())
        val nations = nationRepository.findByWorldId(world.id.toLong())
        val generals = generalRepository.findByWorldId(world.id.toLong())

        processIncome(world, nations, cities, generals)
        processWarIncome(nations, cities)

        if (world.currentMonth.toInt() == 1 || world.currentMonth.toInt() == 7) {
            processSemiAnnual(world, nations, cities, generals)
        }

        updateCitySupply(world, nations, cities, generals)
        updateNationLevel(nations, cities)

        cityRepository.saveAll(cities)
        nationRepository.saveAll(nations)
        generalRepository.saveAll(generals)
    }

    // ── Phase A1: processIncome (legacy formula) ──

    private fun processIncome(world: WorldState, nations: List<Nation>, cities: List<City>, generals: List<General>) {
        val citiesByNation = cities.groupBy { it.nationId }
        val generalsByNation = generals.filter { it.npcState.toInt() != 5 }.groupBy { it.nationId }

        // Count officers (officer_level 2-4) per city who are in their assigned city
        val officerCountByCity = generals
            .filter { it.officerLevel in 2..4 && it.cityId == it.officerCity.toLong() }
            .groupBy { it.cityId }
            .mapValues { it.value.size }

        for (nation in nations) {
            val nationCities = citiesByNation[nation.id] ?: continue
            val nationGenerals = generalsByNation[nation.id] ?: continue
            val taxRate = nation.rateTmp.toDouble()
            val nationLevel = nation.level.toInt().coerceAtLeast(1)

            // Calculate city-level income
            var totalGoldIncome = 0.0
            var totalRiceIncome = 0.0

            for (city in nationCities) {
                if (city.supplyState.toInt() == 0) continue
                val officerCnt = officerCountByCity[city.id] ?: 0
                val isCapital = city.id == nation.capitalCityId

                totalGoldIncome += calcCityGoldIncome(city, officerCnt, isCapital, nationLevel)
                totalRiceIncome += calcCityRiceIncome(city, officerCnt, isCapital, nationLevel)
                totalRiceIncome += calcCityWallIncome(city, officerCnt, isCapital, nationLevel)
            }

            // Apply nation type income modifiers
            val nationTypeMod = NationTypeModifiers.get(nation.typeCode)
            var incomeCtx = IncomeContext()
            if (nationTypeMod != null) {
                incomeCtx = nationTypeMod.onCalcIncome(incomeCtx)
            }

            // Apply tax rate multiplier
            val goldIncome = (totalGoldIncome * taxRate / 20 * incomeCtx.goldMultiplier).toInt()
            val riceIncome = (totalRiceIncome * taxRate / 20 * incomeCtx.riceMultiplier).toInt()

            // Add income to nation treasury
            nation.gold += goldIncome
            nation.rice += riceIncome

            // Calculate bill/salary
            val totalBill = nationGenerals.sumOf { getBill(it.dedication) }
            val goldOutcome = (nation.bill.toDouble() / 100 * totalBill).toInt()
            val riceOutcome = (nation.bill.toDouble() / 100 * totalBill).toInt()

            // Pay salaries (gold)
            val goldRatio = if (totalBill == 0) 0.0
            else if (nation.gold < BASE_GOLD) 0.0
            else if (nation.gold - BASE_GOLD < goldOutcome) {
                val realOutcome = nation.gold - BASE_GOLD
                nation.gold = BASE_GOLD
                realOutcome.toDouble() / totalBill
            } else {
                nation.gold -= goldOutcome
                goldOutcome.toDouble() / totalBill
            }

            // Pay salaries (rice)
            val riceRatio = if (totalBill == 0) 0.0
            else if (nation.rice < BASE_RICE) 0.0
            else if (nation.rice - BASE_RICE < riceOutcome) {
                val realOutcome = nation.rice - BASE_RICE
                nation.rice = BASE_RICE
                realOutcome.toDouble() / totalBill
            } else {
                nation.rice -= riceOutcome
                riceOutcome.toDouble() / totalBill
            }

            // Distribute salary to each general
            for (general in nationGenerals) {
                val bill = getBill(general.dedication)
                general.gold += (bill * goldRatio).toInt()
                general.rice += (bill * riceRatio).toInt()
            }
        }
    }

    private fun calcCityGoldIncome(city: City, officerCnt: Int, isCapital: Boolean, nationLevel: Int): Double {
        if (city.commMax == 0) return 0.0
        val trustRatio = city.trust / 200.0 + 0.5
        var income = city.pop.toDouble() * city.comm / city.commMax * trustRatio / 30
        income *= 1 + city.secu.toDouble() / city.secuMax.coerceAtLeast(1) / 10
        income *= 1.05.pow(officerCnt)
        if (isCapital) {
            income *= 1 + 1.0 / 3 / nationLevel
        }
        return income
    }

    private fun calcCityRiceIncome(city: City, officerCnt: Int, isCapital: Boolean, nationLevel: Int): Double {
        if (city.agriMax == 0) return 0.0
        val trustRatio = city.trust / 200.0 + 0.5
        var income = city.pop.toDouble() * city.agri / city.agriMax * trustRatio / 30
        income *= 1 + city.secu.toDouble() / city.secuMax.coerceAtLeast(1) / 10
        income *= 1.05.pow(officerCnt)
        if (isCapital) {
            income *= 1 + 1.0 / 3 / nationLevel
        }
        return income
    }

    private fun calcCityWallIncome(city: City, officerCnt: Int, isCapital: Boolean, nationLevel: Int): Double {
        if (city.wallMax == 0) return 0.0
        var income = city.def.toDouble() * city.wall / city.wallMax / 3
        income *= 1 + city.secu.toDouble() / city.secuMax.coerceAtLeast(1) / 10
        income *= 1.05.pow(officerCnt)
        if (isCapital) {
            income *= 1 + 1.0 / 3 / nationLevel
        }
        return income
    }

    private fun getDedLevel(dedication: Int): Int {
        return ceil(sqrt(dedication.toDouble()) / 10).toInt().coerceIn(0, MAX_DED_LEVEL)
    }

    private fun getBill(dedication: Int): Int {
        return getDedLevel(dedication) * 200 + 400
    }

    private fun processWarIncome(nations: List<Nation>, cities: List<City>) {
        val nationMap = nations.associateBy { it.id }

        for (city in cities) {
            if (city.dead > 0) {
                val nation = nationMap[city.nationId] ?: continue
                nation.gold += (city.dead / 10)
                val popGain = (city.dead * 0.2).toInt().coerceAtMost((city.popMax - city.pop).coerceAtLeast(0))
                city.pop += popGain
                city.dead = 0
            }
        }
    }

    // ── Phase A2: processSemiAnnual (legacy formula) ──

    private fun processSemiAnnual(world: WorldState, nations: List<Nation>, cities: List<City>, generals: List<General>) {
        // 1. Decay all city stats by 1% and reset dead
        for (city in cities) {
            city.dead = 0
            city.agri = (city.agri * 0.99).toInt()
            city.comm = (city.comm * 0.99).toInt()
            city.secu = (city.secu * 0.99).toInt()
            city.def = (city.def * 0.99).toInt()
            city.wall = (city.wall * 0.99).toInt()
        }

        // 2. Neutral city handling: reset trust to 50, apply additional 0.99 decay (legacy double-decay)
        for (city in cities) {
            if (city.nationId == 0L) {
                city.trust = 50F
                city.agri = (city.agri * 0.99).toInt()
                city.comm = (city.comm * 0.99).toInt()
                city.secu = (city.secu * 0.99).toInt()
                city.def = (city.def * 0.99).toInt()
                city.wall = (city.wall * 0.99).toInt()
            }
        }

        // 3. Population and infrastructure growth per nation (supplied cities only)
        val citiesByNation = cities.filter { it.nationId != 0L }.groupBy { it.nationId }
        val nationMap = nations.associateBy { it.id }

        for ((nationId, nationCities) in citiesByNation) {
            val nation = nationMap[nationId] ?: continue
            val taxRate = nation.rateTmp.toDouble()

            val popRatio = (30 - taxRate) / 200
            val genericRatio = (20 - taxRate) / 200

            // Nation type pop growth modifier
            val nationTypeMod = NationTypeModifiers.get(nation.typeCode)
            var incomeCtx = IncomeContext()
            if (nationTypeMod != null) {
                incomeCtx = nationTypeMod.onCalcIncome(incomeCtx)
            }

            for (city in nationCities) {
                if (city.supplyState.toInt() != 1) continue

                // Population growth (with nation type modifier)
                val rawPopGrowth = if (popRatio >= 0) {
                    BASE_POP_INCREASE + (city.pop * (1 + popRatio * (1 + city.secu.toDouble() / city.secuMax.coerceAtLeast(1) / 10))).toInt()
                } else {
                    BASE_POP_INCREASE + (city.pop * (1 + popRatio * (1 - city.secu.toDouble() / city.secuMax.coerceAtLeast(1) / 10))).toInt()
                }
                val popDelta = rawPopGrowth - city.pop
                val popGrowth = city.pop + (popDelta * incomeCtx.popGrowthMultiplier).toInt()
                city.pop = popGrowth.coerceAtMost(city.popMax)

                // Infrastructure growth
                city.agri = (city.agri * (1 + genericRatio)).toInt().coerceAtMost(city.agriMax)
                city.comm = (city.comm * (1 + genericRatio)).toInt().coerceAtMost(city.commMax)
                city.secu = (city.secu * (1 + genericRatio)).toInt().coerceAtMost(city.secuMax)
                city.def = (city.def * (1 + genericRatio)).toInt().coerceAtMost(city.defMax)
                city.wall = (city.wall * (1 + genericRatio)).toInt().coerceAtMost(city.wallMax)

                // Trust adjustment
                city.trust = (city.trust + (20 - taxRate).toFloat()).coerceIn(0F, 100F)
            }
        }

        // 4. General maintenance: resource decay
        for (general in generals) {
            // Gold: > 10000 → 3%, > 1000 → 1%
            if (general.gold > 10000) {
                general.gold = (general.gold * 0.97).toInt()
            } else if (general.gold > 1000) {
                general.gold = (general.gold * 0.99).toInt()
            }
            // Rice: > 10000 → 3%, > 1000 → 1%
            if (general.rice > 10000) {
                general.rice = (general.rice * 0.97).toInt()
            } else if (general.rice > 1000) {
                general.rice = (general.rice * 0.99).toInt()
            }
        }

        // 5. Nation maintenance: resource decay
        for (nation in nations) {
            // Gold: > 100000 → 5%, > 10000 → 3%, > 1000 → 1%
            nation.gold = when {
                nation.gold > 100000 -> (nation.gold * 0.95).toInt()
                nation.gold > 10000 -> (nation.gold * 0.97).toInt()
                nation.gold > 1000 -> (nation.gold * 0.99).toInt()
                else -> nation.gold
            }
            // Rice
            nation.rice = when {
                nation.rice > 100000 -> (nation.rice * 0.95).toInt()
                nation.rice > 10000 -> (nation.rice * 0.97).toInt()
                nation.rice > 1000 -> (nation.rice * 0.99).toInt()
                else -> nation.rice
            }
        }
    }

    /**
     * Public entry point for per-turn supply state recalculation (traffic update).
     * Called by TurnService each turn to keep supply routes current.
     */
    @Transactional
    fun updateCitySupplyState(world: WorldState) {
        val cities = cityRepository.findByWorldId(world.id.toLong())
        val nations = nationRepository.findByWorldId(world.id.toLong())
        val generals = generalRepository.findByWorldId(world.id.toLong())
        updateCitySupply(world, nations, cities, generals)
        cityRepository.saveAll(cities)
        generalRepository.saveAll(generals)
    }

    /**
     * Public entry point for event-driven income processing.
     */
    @Transactional
    fun processIncomeEvent(world: WorldState) {
        val cities = cityRepository.findByWorldId(world.id.toLong())
        val nations = nationRepository.findByWorldId(world.id.toLong())
        val generals = generalRepository.findByWorldId(world.id.toLong())
        processIncome(world, nations, cities, generals)
        cityRepository.saveAll(cities)
        nationRepository.saveAll(nations)
        generalRepository.saveAll(generals)
    }

    /**
     * Public entry point for event-driven semi-annual processing.
     */
    @Transactional
    fun processSemiAnnualEvent(world: WorldState) {
        val cities = cityRepository.findByWorldId(world.id.toLong())
        val nations = nationRepository.findByWorldId(world.id.toLong())
        val generals = generalRepository.findByWorldId(world.id.toLong())
        processSemiAnnual(world, nations, cities, generals)
        cityRepository.saveAll(cities)
        nationRepository.saveAll(nations)
        generalRepository.saveAll(generals)
    }

    /**
     * Public entry point for event-driven nation level update.
     */
    @Transactional
    fun updateNationLevelEvent(world: WorldState) {
        val cities = cityRepository.findByWorldId(world.id.toLong())
        val nations = nationRepository.findByWorldId(world.id.toLong())
        updateNationLevel(nations, cities)
        nationRepository.saveAll(nations)
    }

    // ── Phase A2: updateCitySupply (+ trust < 30 neutral conversion) ──

    private fun updateCitySupply(world: WorldState, nations: List<Nation>, cities: List<City>, generals: List<General>) {
        val mapCode = (world.config["mapCode"] as? String) ?: "che"
        val citiesByNation = cities.groupBy { it.nationId }
        val cityById = cities.associateBy { it.id }
        val generalsByCity = generals.groupBy { it.cityId }

        // Neutral cities are always supplied
        for (city in cities) {
            if (city.nationId == 0L) {
                city.supplyState = 1
            }
        }

        for (nation in nations) {
            val nationCities = citiesByNation[nation.id] ?: continue
            val supplied = mutableSetOf<Long>()

            val capitalId = nation.capitalCityId
            if (capitalId != null && cityById.containsKey(capitalId)) {
                val queue = ArrayDeque<Long>()
                queue.add(capitalId)
                supplied.add(capitalId)

                while (queue.isNotEmpty()) {
                    val currentId = queue.removeFirst()
                    val adjacentIds = try {
                        mapService.getAdjacentCities(mapCode, currentId.toInt()).map { it.toLong() }
                    } catch (e: Exception) {
                        log.warn("Failed to get adjacent cities for cityId={}: {}", currentId, e.message)
                        emptyList()
                    }
                    for (adjId in adjacentIds) {
                        if (adjId !in supplied) {
                            val adjCity = cityById[adjId]
                            if (adjCity != null && adjCity.nationId == nation.id) {
                                supplied.add(adjId)
                                queue.add(adjId)
                            }
                        }
                    }
                }
            }

            for (city in nationCities) {
                if (city.id in supplied) {
                    city.supplyState = 1
                } else {
                    city.supplyState = 0
                    // Unsupplied city: 10% stat decay
                    city.pop = (city.pop * 0.9).toInt()
                    city.trust = city.trust * 0.9F
                    city.agri = (city.agri * 0.9).toInt()
                    city.comm = (city.comm * 0.9).toInt()
                    city.secu = (city.secu * 0.9).toInt()
                    city.def = (city.def * 0.9).toInt()
                    city.wall = (city.wall * 0.9).toInt()

                    // Unsupplied city generals: 5% crew/train/atmos decay
                    val cityGenerals = generalsByCity[city.id] ?: emptyList()
                    for (general in cityGenerals) {
                        general.crew = (general.crew * 0.95).toInt()
                        general.atmos = (general.atmos * 0.95).toInt().toShort()
                        general.train = (general.train * 0.95).toInt().toShort()
                    }

                    // Trust < 30: convert to neutral
                    if (city.trust < 30) {
                        log.info("City {} (id={}) lost to isolation (trust={})", city.name, city.id, city.trust)
                        // Reset officers in this city
                        for (general in cityGenerals) {
                            if (general.officerCity == city.id.toInt()) {
                                general.officerLevel = 1
                                general.officerCity = 0
                            }
                        }
                        city.nationId = 0
                        city.officerSet = 0
                        city.conflict = mutableMapOf()
                        city.term = 0
                        city.frontState = 0
                    }
                }
            }
        }
    }

    // ── Phase A2: updateNationLevel (legacy thresholds + rewards) ──

    private fun updateNationLevel(nations: List<Nation>, cities: List<City>) {
        val citiesByNation = cities.groupBy { it.nationId }

        for (nation in nations) {
            val nationCities = citiesByNation[nation.id] ?: continue
            val highCities = nationCities.count { it.level >= 4 }

            // Find highest level matching threshold
            var newLevel = 0
            for (level in NATION_LEVEL_THRESHOLDS.indices) {
                if (highCities >= NATION_LEVEL_THRESHOLDS[level]) {
                    newLevel = level
                }
            }

            if (newLevel > nation.level.toInt()) {
                val levelDiff = newLevel - nation.level.toInt()
                nation.level = newLevel.toShort()
                // Level-up reward: gold and rice
                nation.gold += newLevel * 1000
                nation.rice += newLevel * 1000
                log.info("Nation {} leveled up to {} (reward: gold={}, rice={})",
                    nation.name, newLevel, newLevel * 1000, newLevel * 1000)
            }
        }
    }

    /**
     * 연초 통계: 국가 국력(power)·장수수(gennum) 갱신 및 연감 기록.
     * legacy checkStatistic() + postUpdateMonthly() 패러티.
     * TurnService에서 매년 1월에 호출.
     *
     * 국력 = (자원/100 + 기술 + 도시파워 + 장수능력 + 숙련/1000 + 경험공헌/100) / 10
     */
    @Transactional
    fun processYearlyStatistics(world: WorldState) {
        val nations = nationRepository.findByWorldId(world.id.toLong())
        val cities = cityRepository.findByWorldId(world.id.toLong())
        val generals = generalRepository.findByWorldId(world.id.toLong())

        val citiesByNation = cities.groupBy { it.nationId }
        val generalsByNation = generals.filter { it.npcState.toInt() != 5 }.groupBy { it.nationId }

        for (nation in nations) {
            if (nation.level.toInt() == 0) continue

            val nationGenerals = generalsByNation[nation.id] ?: emptyList()
            val nationCities = citiesByNation[nation.id] ?: emptyList()

            // 자원: (국가금+쌀 + 장수금+쌀합) / 100
            val generalGoldRice = nationGenerals.sumOf { (it.gold + it.rice).toLong() }
            val resource = ((nation.gold + nation.rice).toLong() + generalGoldRice) / 100.0

            // 기술
            val tech = nation.tech.toDouble()

            // 도시파워: sum(pop) * sum(pop+agri+comm+secu+wall+def) / sum(popMax+agriMax+commMax+secuMax+wallMax+defMax) / 100
            val suppliedCities = nationCities.filter { it.supplyState.toInt() == 1 }
            val cityPower = if (suppliedCities.isNotEmpty()) {
                val popSum = suppliedCities.sumOf { it.pop.toLong() }
                val valueSum = suppliedCities.sumOf { (it.pop + it.agri + it.comm + it.secu + it.wall + it.def).toLong() }
                val maxSum = suppliedCities.sumOf { (it.popMax + it.agriMax + it.commMax + it.secuMax + it.wallMax + it.defMax).toLong() }
                if (maxSum > 0) (popSum * valueSum).toDouble() / maxSum / 100.0 else 0.0
            } else 0.0

            // 장수능력: (npcMul * leaderCore * 2 + (sqrt(intel*str)*2 + lead/2)/2) 합
            val statPower = nationGenerals.sumOf { g ->
                val lead = g.leadership.toDouble()
                val str = g.strength.toDouble()
                val intel = g.intel.toDouble()
                val npcMul = if (g.npcState < 2) 1.2 else 1.0
                val leaderCore = if (lead >= 40) lead else 0.0
                npcMul * leaderCore * 2 + (kotlin.math.sqrt(intel * str) * 2 + lead / 2) / 2
            }

            // 숙련: sum(dex1..dex5) / 1000
            val dexSum = nationGenerals.sumOf { (it.dex1 + it.dex2 + it.dex3 + it.dex4 + it.dex5).toLong() }
            val dexPower = dexSum / 1000.0

            // 경험공헌: sum(experience+dedication) / 100
            val expDed = nationGenerals.sumOf { (it.experience + it.dedication).toLong() } / 100.0

            val power = ((resource + tech + cityPower + statPower + dexPower + expDed) / 10.0).toInt()

            // 최대 국력 기록
            val prevMaxPower = (nation.meta["maxPower"] as? Number)?.toInt() ?: 0
            if (power > prevMaxPower) {
                nation.meta["maxPower"] = power
            }

            nation.power = power
        }

        nationRepository.saveAll(nations)
        log.info("[World {}] Yearly statistics updated for {} nations", world.id, nations.size)
    }

    // ── Phase A3: processDisasterOrBoom ──

    fun processDisasterOrBoom(world: WorldState) {
        val startYear = try {
            (world.config["startYear"] as? Number)?.toInt() ?: world.currentYear.toInt()
        } catch (_: Exception) {
            world.currentYear.toInt()
        }

        // Skip first 3 years
        if (startYear + 3 > world.currentYear.toInt()) return

        val cities = cityRepository.findByWorldId(world.id.toLong())
        val month = world.currentMonth.toInt()

        // Reset disaster state
        for (city in cities) {
            if (city.state <= 10) {
                city.state = 0
            }
        }

        // Boom probability by month (4,7 = 25%, others = 0)
        val boomRate = when (month) {
            4, 7 -> 0.25
            else -> 0.0
        }

        val hiddenSeed = (world.config["hiddenSeed"] as? String) ?: "${world.id}"
        val rng = DeterministicRng.create(
            hiddenSeed, "disaster",
            world.currentYear, world.currentMonth
        )
        val isGood = boomRate > 0 && rng.nextDouble() < boomRate

        val targetCities = mutableListOf<City>()
        for (city in cities) {
            val secuRatio = if (city.secuMax > 0) city.secu.toDouble() / city.secuMax else 0.0
            val raiseProp = if (isGood) {
                0.02 + secuRatio * 0.05  // 2~7%
            } else {
                0.06 - secuRatio * 0.05  // 1~6%
            }
            if (rng.nextDouble() < raiseProp) {
                targetCities.add(city)
            }
        }

        if (targetCities.isEmpty()) {
            cityRepository.saveAll(cities)
            return
        }

        // Disaster state codes by month
        val disasterCodes = mapOf(
            1 to listOf(4, 5, 3, 9),    // 역병, 지진, 동해, 황건적
            4 to listOf(7, 5, 6),        // 홍수, 지진, 태풍
            7 to listOf(8, 5, 8),        // 메뚜기, 지진, 흉년
            10 to listOf(3, 5, 3, 9),    // 혹한, 지진, 폭설, 황건적
        )
        val boomCodes = mapOf(
            4 to 2,   // 호황
            7 to 1,   // 풍작
        )

        if (isGood) {
            val stateCode = boomCodes[month]?.toShort() ?: 0
            for (city in targetCities) {
                val secuRatio = if (city.secuMax > 0) city.secu.toDouble() / city.secuMax / 0.8 else 0.0
                val affectRatio = 1.01 + secuRatio.coerceIn(0.0, 1.0) * 0.04

                city.state = stateCode
                city.pop = (city.pop * affectRatio).toInt().coerceAtMost(city.popMax)
                city.trust = (city.trust * affectRatio.toFloat()).coerceAtMost(100F)
                city.agri = (city.agri * affectRatio).toInt().coerceAtMost(city.agriMax)
                city.comm = (city.comm * affectRatio).toInt().coerceAtMost(city.commMax)
                city.secu = (city.secu * affectRatio).toInt().coerceAtMost(city.secuMax)
                city.def = (city.def * affectRatio).toInt().coerceAtMost(city.defMax)
                city.wall = (city.wall * affectRatio).toInt().coerceAtMost(city.wallMax)
            }
        } else {
            val codes = disasterCodes[month] ?: disasterCodes[1]!!
            val stateCode = codes[rng.nextInt(codes.size)].toShort()
            for (city in targetCities) {
                val secuRatio = if (city.secuMax > 0) city.secu.toDouble() / city.secuMax / 0.8 else 0.0
                val affectRatio = 0.8 + secuRatio.coerceIn(0.0, 1.0) * 0.15

                city.state = stateCode
                city.pop = (city.pop * affectRatio).toInt()
                city.trust = city.trust * affectRatio.toFloat()
                city.agri = (city.agri * affectRatio).toInt()
                city.comm = (city.comm * affectRatio).toInt()
                city.secu = (city.secu * affectRatio).toInt()
                city.def = (city.def * affectRatio).toInt()
                city.wall = (city.wall * affectRatio).toInt()
            }
        }

        cityRepository.saveAll(cities)
    }

    // ── Phase A3: randomizeCityTradeRate ──

    fun randomizeCityTradeRate(world: WorldState) {
        val cities = cityRepository.findByWorldId(world.id.toLong())
        val hiddenSeed = (world.config["hiddenSeed"] as? String) ?: "${world.id}"
        val rng = DeterministicRng.create(
            hiddenSeed, "tradeRate",
            world.currentYear, world.currentMonth
        )

        // Level-based probability
        val probByLevel = mapOf(
            4 to 0.2, 5 to 0.4, 6 to 0.6, 7 to 0.8, 8 to 1.0
        )

        for (city in cities) {
            val prob = probByLevel[city.level.toInt()] ?: 0.0
            if (prob > 0 && rng.nextDouble() < prob) {
                city.trade = rng.nextInt(95, 106)  // 95~105 inclusive
            }
        }

        cityRepository.saveAll(cities)
    }
}
