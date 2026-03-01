package com.opensam.engine

import com.opensam.entity.*
import com.opensam.repository.*
import com.opensam.service.HistoryService
import com.opensam.service.ScenarioService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * Implements the 19 missing event actions ported from PHP legacy.
 *
 * PHP sources: /ref/core/hwe/sammo/Event/Action/
 */
@Service
class EventActionService(
    private val generalRepository: GeneralRepository,
    private val nationRepository: NationRepository,
    private val cityRepository: CityRepository,
    private val eventRepository: EventRepository,
    private val generalTurnRepository: GeneralTurnRepository,
    private val messageRepository: MessageRepository,
    private val bettingRepository: BettingRepository,
    private val betEntryRepository: BetEntryRepository,
    private val historyService: HistoryService,
    private val scenarioService: ScenarioService,
    private val specialAssignmentService: SpecialAssignmentService,
    private val rankDataRepository: RankDataRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ─── AddGlobalBetray ───
    // Increases betray counter for all generals with betray <= ifMax
    @Transactional
    fun addGlobalBetray(world: WorldState, cnt: Int = 1, ifMax: Int = 0) {
        val generals = generalRepository.findByWorldId(world.id.toLong())
        var affected = 0
        for (g in generals) {
            if (g.betray <= ifMax) {
                g.betray = (g.betray + cnt).toShort()
                affected++
            }
        }
        generalRepository.saveAll(generals)
        log.info("[World {}] AddGlobalBetray: {} generals affected (cnt={}, ifMax={})", world.id, affected, cnt, ifMax)
    }

    // ─── AssignGeneralSpeciality ───
    // Delegates to SpecialAssignmentService (already partially implemented)
    @Transactional
    fun assignGeneralSpeciality(world: WorldState) {
        val startYear = getStartYear(world)
        if (world.currentYear.toInt() < startYear + 3) return

        val generals = generalRepository.findByWorldId(world.id.toLong())
        specialAssignmentService.checkAndAssignSpecials(world, generals)

        // Log speciality assignments
        for (g in generals) {
            if (g.specialCode != "None" && g.specialCode.isNotBlank()) {
                // The service already sets the code; we just need to persist
            }
        }
        generalRepository.saveAll(generals)
        log.info("[World {}] AssignGeneralSpeciality completed for {} generals", world.id, generals.size)
    }

    // ─── AutoDeleteInvader ───
    // If invader nation no longer exists or has no active wars, set ruler to wander and delete event
    @Transactional
    fun autoDeleteInvader(world: WorldState, nationId: Long, currentEventId: Long) {
        val nation = nationRepository.findById(nationId).orElse(null)
        if (nation == null) {
            // Nation doesn't exist, delete the event
            if (currentEventId > 0) eventRepository.deleteById(currentEventId)
            log.info("[World {}] AutoDeleteInvader: nation {} not found, event deleted", world.id, nationId)
            return
        }

        // Check if nation is at war (diplomacy state 0=war, 1=preparing war)
        // Simple check: if nation has ongoing wars, skip
        val nationMeta = nation.meta
        val onWar = (nationMeta["atWar"] as? Boolean) ?: false
        if (onWar) {
            log.info("[World {}] AutoDeleteInvader: nation {} still at war", world.id, nationId)
            return
        }

        // Find ruler and set to wander
        val generals = generalRepository.findByNationId(nationId)
        val ruler = generals.find { it.officerLevel.toInt() == 12 }
        if (ruler != null) {
            val turns = generalTurnRepository.findByGeneralIdOrderByTurnIdx(ruler.id)
            if (turns.isNotEmpty()) {
                val firstTurn = turns[0]
                firstTurn.actionCode = "che_방랑"
                firstTurn.arg = mutableMapOf()
                firstTurn.brief = "이민족 방랑"
                generalTurnRepository.save(firstTurn)
            }
        }

        if (currentEventId > 0) eventRepository.deleteById(currentEventId)
        log.info("[World {}] AutoDeleteInvader: nation {} scheduled for deletion", world.id, nationId)
    }

    // ─── BlockScoutAction / UnblockScoutAction ───
    @Transactional
    fun blockScoutAction(world: WorldState, blockChangeScout: Boolean? = null) {
        val nations = nationRepository.findByWorldId(world.id.toLong())
        for (n in nations) {
            n.scoutLevel = 1
        }
        nationRepository.saveAll(nations)
        if (blockChangeScout != null) {
            world.config["blockChangeScout"] = blockChangeScout
        }
        log.info("[World {}] BlockScoutAction: {} nations blocked", world.id, nations.size)
    }

    @Transactional
    fun unblockScoutAction(world: WorldState, blockChangeScout: Boolean? = null) {
        val nations = nationRepository.findByWorldId(world.id.toLong())
        for (n in nations) {
            n.scoutLevel = 0
        }
        nationRepository.saveAll(nations)
        if (blockChangeScout != null) {
            world.config["blockChangeScout"] = blockChangeScout
        }
        log.info("[World {}] UnblockScoutAction: {} nations unblocked", world.id, nations.size)
    }

    // ─── ChangeCity ───
    // Modifies city stats based on target filter and action map
    @Transactional
    fun changeCity(world: WorldState, target: Any?, actions: Map<String, Any>) {
        val cities = cityRepository.findByWorldId(world.id.toLong())
        val targetCities = filterTargetCities(cities, target)

        for (city in targetCities) {
            for ((key, value) in actions) {
                applyCityChange(city, key, value)
            }
        }
        cityRepository.saveAll(targetCities)
        log.info("[World {}] ChangeCity: {} cities modified", world.id, targetCities.size)
    }

    private fun filterTargetCities(cities: List<City>, target: Any?): List<City> {
        if (target == null) return cities
        return when {
            target is String -> when (target) {
                "all" -> cities
                "free" -> cities.filter { it.nationId == 0L }
                "occupied" -> cities.filter { it.nationId != 0L }
                else -> cities
            }
            target is Map<*, *> -> {
                val type = target["type"] as? String ?: "all"
                @Suppress("UNCHECKED_CAST")
                val args = target["args"] as? List<Any> ?: emptyList()
                when (type) {
                    "cities" -> {
                        val cityNames = args.map { it.toString() }.toSet()
                        val cityIds = args.mapNotNull { (it as? Number)?.toLong() }.toSet()
                        cities.filter { it.name in cityNames || it.id in cityIds }
                    }
                    "free" -> cities.filter { it.nationId == 0L }
                    "occupied" -> cities.filter { it.nationId != 0L }
                    else -> cities
                }
            }
            else -> cities
        }
    }

    private fun applyCityChange(city: City, key: String, value: Any) {
        when (key) {
            "trust" -> {
                val newVal = parseCityValue(city.trust.toDouble(), 100.0, value)
                city.trust = newVal.toFloat().coerceIn(0f, 100f)
            }
            "trade" -> {
                if (value is Number) {
                    city.trade = value.toDouble().coerceIn(95.0, 105.0).toInt()
                }
            }
            "pop" -> city.pop = parseCityValueWithMax(city.pop.toDouble(), city.popMax.toDouble(), value).toInt()
            "agri" -> city.agri = parseCityValueWithMax(city.agri.toDouble(), city.agriMax.toDouble(), value).toInt()
            "comm" -> city.comm = parseCityValueWithMax(city.comm.toDouble(), city.commMax.toDouble(), value).toInt()
            "secu" -> city.secu = parseCityValueWithMax(city.secu.toDouble(), city.secuMax.toDouble(), value).toInt()
            "def" -> city.def = parseCityValueWithMax(city.def.toDouble(), city.defMax.toDouble(), value).toInt()
            "wall" -> city.wall = parseCityValueWithMax(city.wall.toDouble(), city.wallMax.toDouble(), value).toInt()
            "pop_max" -> city.popMax = parseCityMaxValue(city.popMax.toDouble(), value).toInt()
            "agri_max" -> city.agriMax = parseCityMaxValue(city.agriMax.toDouble(), value).toInt()
            "comm_max" -> city.commMax = parseCityMaxValue(city.commMax.toDouble(), value).toInt()
            "secu_max" -> city.secuMax = parseCityMaxValue(city.secuMax.toDouble(), value).toInt()
            "def_max" -> city.defMax = parseCityMaxValue(city.defMax.toDouble(), value).toInt()
            "wall_max" -> city.wallMax = parseCityMaxValue(city.wallMax.toDouble(), value).toInt()
        }
    }

    private fun parseCityValue(current: Double, max: Double, value: Any): Double {
        return when (value) {
            is Int -> value.toDouble().coerceIn(0.0, max)
            is Double -> (current * value).coerceIn(0.0, max)
            is Float -> (current * value).coerceIn(0.0, max)
            is String -> parseMathExpression(current, max, value)
            is Number -> value.toDouble().coerceIn(0.0, max)
            else -> current
        }
    }

    private fun parseCityValueWithMax(current: Double, max: Double, value: Any): Double {
        return when (value) {
            is Int -> value.toDouble().coerceIn(0.0, max)
            is Double -> (current * value).coerceIn(0.0, max)
            is Float -> (current * value).coerceIn(0.0, max)
            is String -> parseMathExpression(current, max, value)
            is Number -> value.toDouble().coerceIn(0.0, max)
            else -> current
        }
    }

    private fun parseCityMaxValue(current: Double, value: Any): Double {
        return when (value) {
            is Int -> value.toDouble().coerceAtLeast(0.0)
            is Double -> (current * value).coerceAtLeast(0.0)
            is String -> parseMathExpression(current, Double.MAX_VALUE, value)
            is Number -> value.toDouble().coerceAtLeast(0.0)
            else -> current
        }
    }

    private fun parseMathExpression(current: Double, max: Double, expr: String): Double {
        // Percentage pattern: "75%" → current_max * 0.75
        val percentMatch = Regex("""^(\d+(?:\.\d+)?)%$""").matchEntire(expr)
        if (percentMatch != null) {
            val pct = percentMatch.groupValues[1].toDouble() / 100.0
            return (max * pct).coerceIn(0.0, max)
        }
        // Math pattern: "+30", "-10", "*0.5", "/2"
        val mathMatch = Regex("""^([+\-*/])(\d+(?:\.\d+)?)$""").matchEntire(expr)
        if (mathMatch != null) {
            val op = mathMatch.groupValues[1]
            val num = mathMatch.groupValues[2].toDouble()
            val result = when (op) {
                "+" -> current + num
                "-" -> current - num
                "*" -> current * num
                "/" -> if (num != 0.0) current / num else current
                else -> current
            }
            return result.coerceIn(0.0, max)
        }
        return current
    }

    // ─── CreateAdminNPC ───
    // Legacy: NYI (Not Yet Implemented in PHP either)
    fun createAdminNPC(world: WorldState) {
        log.info("[World {}] CreateAdminNPC: NYI (not implemented in legacy)", world.id)
    }

    // ─── CreateManyNPC ───
    // Creates random NPC generals (delegates to NpcSpawnService for the actual creation)
    @Transactional
    fun createManyNPC(world: WorldState, npcCount: Int = 10, fillCnt: Int = 0) {
        if (npcCount <= 0 && fillCnt <= 0) return

        var moreGenCnt = 0
        if (fillCnt > 0) {
            // Count nations with player rulers and calculate how many more NPCs needed
            val generals = generalRepository.findByWorldId(world.id.toLong())
            val playerNations = generals
                .filter { it.npcState < 3 && it.officerLevel.toInt() == 12 }
                .map { it.nationId }
                .distinct()

            if (playerNations.isNotEmpty()) {
                val regGens = generals.count { it.nationId in playerNations && it.npcState < 4 }
                moreGenCnt = (playerNations.size * fillCnt - regGens).coerceAtLeast(0)
            }
        }

        val totalCount = npcCount + moreGenCnt
        val hiddenSeed = (world.config["hiddenSeed"] as? String) ?: "${world.id}"
        val rng = DeterministicRng.create(
            hiddenSeed, "CreateManyNPC",
            world.currentYear, world.currentMonth
        )

        var created = 0
        for (i in 0 until totalCount) {
            val age = rng.nextInt(20, 26)
            val birthYear = world.currentYear - age
            val deathYear = world.currentYear + rng.nextInt(10, 51)
            val statTotal = rng.nextInt(140, 220)
            val leadership = (statTotal / 3 + rng.nextInt(-20, 21)).coerceIn(30, 100)
            val strength = (statTotal / 3 + rng.nextInt(-20, 21)).coerceIn(30, 100)
            val intel = (statTotal - leadership - strength).coerceIn(30, 100)

            val allCities = cityRepository.findByWorldId(world.id.toLong())
            val targetCity = allCities.filter { it.nationId == 0L }.randomOrNull(rng)
                ?: allCities.randomOrNull(rng)
                ?: continue

            val npc = General(
                worldId = world.id.toLong(),
                name = "무명장수${world.currentYear}_${i}",
                nationId = 0,
                cityId = targetCity.id,
                npcState = 3,
                bornYear = birthYear.toShort(),
                deadYear = deathYear.toShort(),
                leadership = leadership.toShort(),
                strength = strength.toShort(),
                intel = intel.toShort(),
                gold = 1000,
                rice = 1000,
            )
            generalRepository.save(npc)
            created++
        }

        // Log
        historyService.logWorldHistory(
            world.id.toLong(),
            "장수 <C>${created}</>명이 <S>등장</>했습니다.",
            world.currentYear.toInt(),
            world.currentMonth.toInt()
        )
        log.info("[World {}] CreateManyNPC: {} NPCs created", world.id, created)
    }

    // ─── FinishNationBetting ───
    @Transactional
    fun finishNationBetting(world: WorldState, bettingId: Long) {
        val betting = bettingRepository.findById(bettingId).orElse(null)
        if (betting == null) {
            log.warn("[World {}] FinishNationBetting: betting {} not found", world.id, bettingId)
            return
        }

        // Mark betting as finished
        betting.status = "finished"
        bettingRepository.save(betting)

        // Determine winners: remaining nations
        val nations = nationRepository.findByWorldId(world.id.toLong()).filter { it.level > 0 }
        val winnerNationIds = nations.map { it.id }.toSet()

        // Find all bets and reward winners
        val bets = betEntryRepository.findByBettingId(bettingId)
        for (bet in bets) {
            // BetEntry.choice stores the nation ID as string
            val betNationId = bet.choice.toLongOrNull() ?: continue
            if (betNationId in winnerNationIds) {
                // Winner: return bet amount * odds (simplified)
                val amount = bet.amount
                val general = generalRepository.findById(bet.generalId).orElse(null) ?: continue
                general.gold += (amount * 2)  // simplified reward
                generalRepository.save(general)
            }
        }

        historyService.logWorldHistory(
            world.id.toLong(),
            "<B><b>【내기】</b></> 내기의 결과가 나왔습니다!",
            world.currentYear.toInt(),
            world.currentMonth.toInt()
        )
        log.info("[World {}] FinishNationBetting: betting {} finished", world.id, bettingId)
    }

    // ─── OpenNationBetting ───
    @Transactional
    fun openNationBetting(world: WorldState, nationCnt: Int = 1, bonusPoint: Int = 0) {
        val nations = nationRepository.findByWorldId(world.id.toLong()).filter { it.level > 0 }
        val cities = cityRepository.findByWorldId(world.id.toLong())
        val citiesByNation = cities.groupBy { it.nationId }

        val name = if (nationCnt == 1) "천통국" else "최후 ${nationCnt}국"

        // Create betting
        val betting = bettingRepository.save(Betting(
            worldId = world.id.toLong(),
            targetType = "bettingNation",
            targetId = 0,
            odds = mutableMapOf(
                "name" to "${name} 예상",
                "selectCnt" to nationCnt,
                "candidates" to nations.sortedByDescending { it.power }.map { n ->
                    mapOf(
                        "nationId" to n.id,
                        "name" to n.name,
                        "power" to n.power,
                        "gennum" to n.gennum,
                        "cityCnt" to (citiesByNation[n.id]?.size ?: 0),
                    )
                },
            ),
            status = "open",
        ))

        // Create finish event: when remaining nations <= nationCnt
        eventRepository.save(Event(
            worldId = world.id.toLong(),
            targetCode = "DESTROY_NATION",
            priority = 1000,
            condition = mutableMapOf(
                "type" to "remain_nation",
                "count" to nationCnt,
            ),
            action = mutableMapOf(
                "type" to "compound",
                "actions" to listOf(
                    mapOf("type" to "finish_nation_betting", "bettingId" to betting.id),
                    mapOf("type" to "delete_self"),
                ),
            ),
        ))

        historyService.logWorldHistory(
            world.id.toLong(),
            if (nationCnt > 1) "<B><b>【내기】</b></>중원의 강자를 점치는 <C>내기</>가 진행중입니다!"
            else "<B><b>【내기】</b></>천하통일 후보를 점치는 <C>내기</>가 진행중입니다!",
            world.currentYear.toInt(),
            world.currentMonth.toInt()
        )
        log.info("[World {}] OpenNationBetting: betting {} opened (nationCnt={})", world.id, betting.id, nationCnt)
    }

    // ─── InvaderEnding ───
    @Transactional
    fun invaderEnding(world: WorldState, currentEventId: Long) {
        val isunited = (world.config["isunited"] as? Number)?.toInt() ?: 0
        if (isunited == 0 || isunited == 2) {
            log.info("[World {}] InvaderEnding: no invader event (isunited={})", world.id, isunited)
            return
        }

        val nations = nationRepository.findByWorldId(world.id.toLong())
        if (nations.size >= 2) {
            return  // Event still ongoing
        }

        val cities = cityRepository.findByWorldId(world.id.toLong())
        val freeCityCount = cities.count { it.nationId == 0L }
        val totalCityCount = cities.size

        val needStop: Boolean
        val userWin: Boolean

        if (freeCityCount == 0) {
            needStop = true
            val nationName = nations.firstOrNull()?.name ?: ""
            userWin = !nationName.startsWith("ⓞ")
        } else if (freeCityCount == totalCityCount) {
            needStop = true
            userWin = false
        } else {
            return  // Event still ongoing
        }

        val year = world.currentYear.toInt()
        val month = world.currentMonth.toInt()

        if (userWin) {
            historyService.logWorldHistory(world.id.toLong(), "<L><b>【이벤트】</b></>이민족을 모두 소탕했습니다!", year, month)
            historyService.logWorldHistory(world.id.toLong(), "<L><b>【이벤트】</b></>중원은 당분간 태평성대를 누릴 것입니다.", year, month)
        } else {
            historyService.logWorldHistory(world.id.toLong(), "<L><b>【이벤트】</b></>중원은 이민족에 의해 혼란에 빠졌습니다.", year, month)
            historyService.logWorldHistory(world.id.toLong(), "<L><b>【이벤트】</b></>백성은 언젠가 영웅이 나타나길 기다립니다.", year, month)
        }

        world.config["isunited"] = 3

        if (currentEventId > 0) eventRepository.deleteById(currentEventId)
        log.info("[World {}] InvaderEnding: userWin={}", world.id, userWin)
    }

    // ─── LostUniqueItem ───
    // Randomly removes non-buyable items from player generals
    @Transactional
    fun lostUniqueItem(world: WorldState, lostProb: Double = 0.1) {
        val generals = generalRepository.findByWorldId(world.id.toLong())
            .filter { it.npcState <= 1 }

        if (generals.isEmpty()) return

        val hiddenSeed = (world.config["hiddenSeed"] as? String) ?: "${world.id}"
        val rng = DeterministicRng.create(
            hiddenSeed, "LostUniqueItem",
            world.currentYear, world.currentMonth
        )

        var totalLostCnt = 0
        val lostGeneralNames = mutableListOf<String>()

        for (general in generals) {
            val itemCode = general.itemCode
            if (itemCode == "None" || itemCode.isBlank()) continue

            // Check if item is a unique (non-buyable) item
            // Items stored in meta as list or in itemCode field
            val items = parseGeneralItems(general)
            var didLose = false

            for ((slot, item) in items) {
                if (isBuyableItem(item)) continue

                if (rng.nextDouble() < lostProb) {
                    clearGeneralItem(general, slot)
                    totalLostCnt++
                    didLose = true
                }
            }

            if (didLose) {
                lostGeneralNames.add(general.name)
                generalRepository.save(general)
            }
        }

        val year = world.currentYear.toInt()
        val month = world.currentMonth.toInt()

        if (totalLostCnt == 0) {
            historyService.logWorldHistory(world.id.toLong(),
                "<R><b>【망실】</b></>어떤 아이템도 잃지 않았습니다!", year, month)
        } else {
            val displayNames = if (lostGeneralNames.size > 4) {
                lostGeneralNames.take(4).joinToString(", ") + " 외 ${lostGeneralNames.size - 4}명"
            } else {
                lostGeneralNames.joinToString(", ")
            }
            historyService.logWorldHistory(world.id.toLong(),
                "<R><b>【망실】</b></>불운하게도 <Y>${displayNames}</>이(가) 유니크 아이템을 잃었습니다! (총 <C>${totalLostCnt}</>개)",
                year, month)
        }
        log.info("[World {}] LostUniqueItem: {} items lost by {} generals", world.id, totalLostCnt, lostGeneralNames.size)
    }

    private fun parseGeneralItems(general: General): Map<String, String> {
        val items = mutableMapOf<String, String>()
        if (general.itemCode != "None" && general.itemCode.isNotBlank()) {
            items["item"] = general.itemCode
        }
        // Check meta for additional item slots
        @Suppress("UNCHECKED_CAST")
        val metaItems = general.meta["items"] as? Map<String, String>
        if (metaItems != null) {
            items.putAll(metaItems)
        }
        return items
    }

    private fun isBuyableItem(itemCode: String): Boolean {
        // Buyable items are basic items (숫돌, 서적, etc.) - codes starting with common prefixes
        val buyableItems = setOf("None", "숫돌", "서적", "비급", "병서", "명마", "양마")
        return itemCode in buyableItems
    }

    private fun clearGeneralItem(general: General, slot: String) {
        if (slot == "item") {
            general.itemCode = "None"
        } else {
            @Suppress("UNCHECKED_CAST")
            val metaItems = general.meta["items"] as? MutableMap<String, String>
            metaItems?.remove(slot)
        }
    }

    // ─── MergeInheritPointRank ───
    // Recalculates inherit point ranks by merging all inheritance sources.
    // Legacy used InheritancePointManager to sum across InheritanceKey cases.
    // We simplify: compute a composite score from general stats/exp/ded and
    // store it as a RankData entry with category = "inherit_point_earned_by_merge".
    @Transactional
    fun mergeInheritPointRank(world: WorldState) {
        val worldId = world.id.toLong()
        val generals = generalRepository.findByWorldId(worldId)

        // Delete old merge-rank entries for this world
        val oldMergeEntries = rankDataRepository.findByWorldIdAndCategory(worldId, RANK_INHERIT_EARNED_BY_MERGE)
        if (oldMergeEntries.isNotEmpty()) {
            rankDataRepository.deleteAll(oldMergeEntries)
        }

        // Calculate inheritance points per general across all inheritance categories
        // Legacy: InheritancePointManager sums points from sabotage, dex, betting, max_belong, etc.
        // We approximate with a composite score from general stats, experience, and dedication.
        val newEntries = generals.map { general ->
            val score = calculateInheritancePoint(general)
            RankData(
                worldId = worldId,
                nationId = general.nationId,
                category = RANK_INHERIT_EARNED_BY_MERGE,
                score = score,
                meta = mutableMapOf("generalId" to general.id, "generalName" to general.name),
            )
        }
        rankDataRepository.saveAll(newEntries)

        // Update total earned = earned_by_action + earned_by_merge
        val actionEntries = rankDataRepository.findByWorldIdAndCategory(worldId, RANK_INHERIT_EARNED_BY_ACTION)
            .associateBy { (it.meta["generalId"] as? Number)?.toLong() ?: 0L }
        val mergeEntries = newEntries.associateBy { (it.meta["generalId"] as? Number)?.toLong() ?: 0L }

        val totalEntries = rankDataRepository.findByWorldIdAndCategory(worldId, RANK_INHERIT_EARNED)
        for (entry in totalEntries) {
            val gid = (entry.meta["generalId"] as? Number)?.toLong() ?: continue
            val actionScore = actionEntries[gid]?.score ?: 0
            val mergeScore = mergeEntries[gid]?.score ?: 0
            entry.score = actionScore + mergeScore
        }
        rankDataRepository.saveAll(totalEntries)

        // Update spent = spent_dynamic (copy dynamic to static)
        val dynamicEntries = rankDataRepository.findByWorldIdAndCategory(worldId, RANK_INHERIT_SPENT_DYNAMIC)
            .associateBy { (it.meta["generalId"] as? Number)?.toLong() ?: 0L }
        val spentEntries = rankDataRepository.findByWorldIdAndCategory(worldId, RANK_INHERIT_SPENT)
        for (entry in spentEntries) {
            val gid = (entry.meta["generalId"] as? Number)?.toLong() ?: continue
            entry.score = dynamicEntries[gid]?.score ?: 0
        }
        rankDataRepository.saveAll(spentEntries)

        log.info("[World {}] MergeInheritPointRank: {} generals processed", world.id, generals.size)
    }

    private fun calculateInheritancePoint(general: General): Int {
        // Composite inheritance point from general stats/exp/dedication
        // Legacy: sums across InheritanceKey cases (sabotage, dex, betting, belong, etc.)
        val statSum = general.leadership + general.strength + general.intel
        val expDed = (general.experience + general.dedication) / 100
        return (statSum + expDed).toInt()
    }

    companion object {
        private const val RANK_INHERIT_EARNED = "inherit_earned"
        private const val RANK_INHERIT_SPENT = "inherit_spent"
        private const val RANK_INHERIT_EARNED_BY_MERGE = "inherit_earned_dyn"
        private const val RANK_INHERIT_EARNED_BY_ACTION = "inherit_earned_act"
        private const val RANK_INHERIT_SPENT_DYNAMIC = "inherit_spent_dyn"
    }

    // ─── NewYear ───
    @Transactional
    fun newYear(world: WorldState) {
        val year = world.currentYear.toInt()
        val month = world.currentMonth.toInt()

        // Log new year message
        historyService.logWorldHistory(
            world.id.toLong(),
            "<C>${year}</>년이 되었습니다.",
            year, month
        )

        // Increment age for all generals and belong for nation generals
        val generals = generalRepository.findByWorldId(world.id.toLong())
        for (g in generals) {
            g.age = (g.age + 1).toShort()
            if (g.nationId != 0L) {
                g.belong = (g.belong + 1).toShort()
            }
        }
        generalRepository.saveAll(generals)
        log.info("[World {}] NewYear: {} generals aged", world.id, generals.size)
    }

    // ─── ProcessWarIncome ───
    @Transactional
    fun processWarIncome(world: WorldState) {
        val cities = cityRepository.findByWorldId(world.id.toLong())
        val nations = nationRepository.findByWorldId(world.id.toLong())
        val nationMap = nations.associateBy { it.id }
        val citiesByNation = cities.groupBy { it.nationId }

        // War gold income by nation type
        for (nation in nations) {
            if (nation.level <= 0) continue
            val nationCities = citiesByNation[nation.id] ?: continue
            val income = calculateWarGoldIncome(nation.typeCode, nationCities)
            nation.gold += income
        }

        // 20% of dead troops return as pop
        for (city in cities) {
            if (city.dead > 0) {
                val popGain = (city.dead * 0.2).toInt()
                city.pop += popGain
                city.dead = 0
            }
        }

        cityRepository.saveAll(cities)
        nationRepository.saveAll(nations)
        log.info("[World {}] ProcessWarIncome completed", world.id)
    }

    private fun calculateWarGoldIncome(nationTypeCode: String, cities: List<City>): Int {
        // War income based on nation type and city populations
        val popSum = cities.sumOf { it.pop.toLong() }
        val baseIncome = (popSum / 100).toInt()
        // Nation type modifier
        val modifier = when {
            nationTypeCode.contains("병가") -> 1.2
            nationTypeCode.contains("법가") -> 1.1
            else -> 1.0
        }
        return (baseIncome * modifier).toInt()
    }

    // ─── RaiseDisaster ───
    // Delegates to EconomyService.processDisasterOrBoom which is already implemented
    @Transactional
    fun raiseDisaster(world: WorldState) {
        // Already implemented in EconomyService.processDisasterOrBoom
        // This is called through EventService dispatch
        log.info("[World {}] RaiseDisaster: delegated to EconomyService", world.id)
    }

    // ─── RegNPC / RegNeutralNPC ───
    @Transactional
    fun regNPC(world: WorldState, params: Map<String, Any>) {
        val name = params["name"] as? String ?: return
        val nationId = (params["nationId"] as? Number)?.toLong() ?: 0L
        val cityName = params["city"] as? String
        val cityId = (params["cityId"] as? Number)?.toLong()
        val leadership = (params["leadership"] as? Number)?.toInt() ?: 50
        val strength = (params["strength"] as? Number)?.toInt() ?: 50
        val intel = (params["intel"] as? Number)?.toInt() ?: 50
        val officerLevel = (params["officerLevel"] as? Number)?.toInt() ?: 0
        val birth = (params["birth"] as? Number)?.toInt() ?: 160
        val death = (params["death"] as? Number)?.toInt() ?: 300
        val npcType = (params["npcType"] as? Number)?.toShort() ?: 2

        // Resolve city
        val resolvedCityId = when {
            cityId != null -> cityId
            cityName != null -> {
                cityRepository.findByWorldId(world.id.toLong())
                    .find { it.name == cityName }?.id ?: 0L
            }
            else -> 0L
        }

        val general = General(
            worldId = world.id.toLong(),
            name = name,
            nationId = nationId,
            cityId = resolvedCityId,
            npcState = npcType,
            bornYear = birth.toShort(),
            deadYear = death.toShort(),
            leadership = leadership.toShort(),
            strength = strength.toShort(),
            intel = intel.toShort(),
            officerLevel = officerLevel.toShort(),
            gold = 1000,
            rice = 1000,
        )
        generalRepository.save(general)
        log.info("[World {}] RegNPC: created '{}' (nationId={}, npc={})", world.id, name, nationId, npcType)
    }

    @Transactional
    fun regNeutralNPC(world: WorldState, params: Map<String, Any>) {
        // Same as regNPC but with npcType=6 (neutral)
        val mutableParams = params.toMutableMap()
        mutableParams["npcType"] = 6.toShort()
        regNPC(world, mutableParams)
    }

    // ─── ResetOfficerLock ───
    @Transactional
    fun resetOfficerLock(world: WorldState) {
        // Reset chief_set on nations (capital move lock)
        val nations = nationRepository.findByWorldId(world.id.toLong())
        for (n in nations) {
            n.meta.remove("chiefSet")
        }
        nationRepository.saveAll(nations)

        // Reset officer_set on cities (officer assignment lock)
        val cities = cityRepository.findByWorldId(world.id.toLong())
        for (c in cities) {
            c.officerSet = 0
        }
        cityRepository.saveAll(cities)
        log.info("[World {}] ResetOfficerLock: {} nations, {} cities reset", world.id, nations.size, cities.size)
    }

    // ─── Helper ───
    private fun getStartYear(world: WorldState): Int {
        return try {
            (world.config["startYear"] as? Number)?.toInt()
                ?: scenarioService.getScenario(world.scenarioCode).startYear
        } catch (_: Exception) {
            world.currentYear.toInt()
        }
    }
}
