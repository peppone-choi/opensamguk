package com.opensam.engine.ai

import com.opensam.engine.DeterministicRng
import com.opensam.entity.City
import com.opensam.entity.Diplomacy
import com.opensam.entity.General
import com.opensam.entity.Nation
import com.opensam.entity.WorldState
import com.opensam.repository.CityRepository
import com.opensam.repository.DiplomacyRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Full NPC AI decision engine, ported from legacy GeneralAI.php.
 *
 * Returns action command strings that the game engine interprets.
 * The decision tree mirrors the legacy PHP implementation's ~40 do*() methods.
 */
@Service
class GeneralAI(
    private val generalRepository: GeneralRepository,
    private val cityRepository: CityRepository,
    private val nationRepository: NationRepository,
    private val diplomacyRepository: DiplomacyRepository,
) {
    private val logger = LoggerFactory.getLogger(GeneralAI::class.java)

    // ──────────────────────────────────────────────────────────
    //  Main entry point
    // ──────────────────────────────────────────────────────────

    fun decideAndExecute(general: General, world: WorldState): String {
        val rng = DeterministicRng.create(
            "${world.id}", "GeneralAI", world.currentYear, world.currentMonth, general.id
        )

        // Troop leaders (npcState=5) always rally
        if (general.npcState.toInt() == 5) {
            logger.debug("General {} ({}) is troop leader, always 집합", general.id, general.name)
            return "집합"
        }

        // Wanderers (nationId=0) have limited options
        if (general.nationId == 0L) {
            return decideWandererAction(general, world, rng)
        }

        val worldId = world.id.toLong()
        val city = cityRepository.findById(general.cityId).orElse(null) ?: return "휴식"
        val nation = nationRepository.findById(general.nationId).orElse(null)

        val allCities = cityRepository.findByWorldId(worldId)
        val allGenerals = generalRepository.findByWorldId(worldId)
        val allNations = nationRepository.findByWorldId(worldId)
        val diplomacies = diplomacyRepository.findByWorldIdAndIsDeadFalse(worldId)

        val nationCities = if (nation != null) {
            allCities.filter { it.nationId == nation.id }
        } else {
            emptyList()
        }

        val frontCities = nationCities.filter { it.frontState > 0 }
        val rearCities = nationCities.filter { it.frontState.toInt() == 0 }
        val supplyCities = nationCities.filter { it.supplyState > 0 }
        val backupCities = nationCities.filter { it.frontState.toInt() == 0 && it.supplyState > 0 }
        val nationGenerals = allGenerals.filter { it.nationId == general.nationId }

        val diplomacyState = calcDiplomacyState(nation, diplomacies)

        val nationPolicy = if (nation != null) {
            NpcPolicyBuilder.buildNationPolicy(nation.meta)
        } else {
            NpcNationPolicy()
        }
        val generalType = classifyGeneral(general, rng, nationPolicy.minNPCWarLeadership)

        // Determine attackable status (any front city with supply)
        val attackable = frontCities.any { it.supplyState > 0 }

        // War target nations
        val warTargetNations = calcWarTargetNations(nation, diplomacies)

        val ctx = AIContext(
            world = world,
            general = general,
            city = city,
            nation = nation,
            diplomacyState = diplomacyState,
            generalType = generalType,
            allCities = allCities,
            allGenerals = allGenerals,
            allNations = allNations,
            frontCities = frontCities,
            rearCities = rearCities,
            nationGenerals = nationGenerals,
        )

        val generalPolicy = if (nation != null) {
            NpcPolicyBuilder.buildGeneralPolicy(nation.meta)
        } else {
            NpcGeneralPolicy()
        }

        // Check reserved command
        val reservedAction = checkReservedCommand(general)
        if (reservedAction != null) {
            logger.debug("General {} ({}) using reserved command: {}", general.id, general.name, reservedAction)
            return reservedAction
        }

        // Injury check
        if (general.injury > nationPolicy.cureThreshold) {
            logger.debug("General {} ({}) injury {} exceeds cureThreshold {}", general.id, general.name, general.injury, nationPolicy.cureThreshold)
            return "요양"
        }

        // NPC거병 check: NPC lord-capable wanderer with nation
        if ((general.npcState.toInt() == 2 || general.npcState.toInt() == 3) && general.nationId == 0L) {
            val riseResult = doRise(general, world, rng)
            if (riseResult != null) return riseResult
        }

        // 사망대비: if killTurn is low, handle death preparation
        val killTurn = general.killTurn?.toInt()
        if (killTurn != null && killTurn <= 5 && general.npcState.toInt() >= 2) {
            return doDeathPreparation(general, nation, rng)
        }

        val action = try {
            when {
                general.officerLevel >= 12 && nation != null -> decideChiefAction(
                    ctx, rng, nationPolicy, generalPolicy, attackable, warTargetNations, supplyCities, backupCities
                )
                diplomacyState == DiplomacyState.AT_WAR || diplomacyState == DiplomacyState.RECRUITING -> decideWarAction(
                    ctx, rng, generalPolicy, nationPolicy, attackable, warTargetNations, supplyCities, backupCities
                )
                diplomacyState == DiplomacyState.IMMINENT -> decideWarAction(
                    ctx, rng, generalPolicy, nationPolicy, attackable, warTargetNations, supplyCities, backupCities
                )
                else -> decidePeaceAction(
                    ctx, rng, generalPolicy, nationPolicy, supplyCities, backupCities
                )
            }
        } catch (e: Exception) {
            logger.warn("AI decision failed for general ${general.id}: ${e.message}")
            "휴식"
        }

        logger.debug(
            "General {} ({}) decided: {} [diplo={}, type={}]",
            general.id,
            general.name,
            action,
            diplomacyState,
            generalType,
        )
        return action
    }

    // ──────────────────────────────────────────────────────────
    //  Reserved command check
    // ──────────────────────────────────────────────────────────

    private fun checkReservedCommand(general: General): String? {
        val reserved = general.meta["reservedCommand"] as? String ?: return null
        general.meta.remove("reservedCommand")
        if (reserved == "휴식" || reserved.isBlank()) return null
        return reserved
    }

    // ──────────────────────────────────────────────────────────
    //  Wanderer AI (nationId=0)
    // ──────────────────────────────────────────────────────────

    /**
     * Legacy: chooseGeneralTurn for nationID==0.
     * Wanderers can: 국가선택 (join nation), 거병 (rise), 이동, 견문, 물자조달, 휴식.
     */
    private fun decideWandererAction(general: General, world: WorldState, rng: Random): String {
        if (general.injury > 0) return "요양"

        // NPC lords (officerLevel==12) with no capital do 방랑군이동 / 건국
        if (general.npcState.toInt() >= 2 && general.officerLevel.toInt() == 12) {
            // Try 건국 first
            if (general.makeLimit.toInt() == 0) {
                if (rng.nextDouble() < 0.01) return "건국"
            }
            // Move toward candidate city
            if (rng.nextDouble() < 0.6) return "이동"
            return "인재탐색"
        }

        // 국가선택: try to join a nation
        if (general.npcState.toInt() >= 2) {
            // Per legacy: 30% chance to try 랜덤임관
            if (rng.nextDouble() < 0.3) {
                // Additional gate from legacy: early game / late game probability
                val yearsElapsed = world.currentYear - ((world.config["startyear"] as? Number)?.toInt() ?: world.currentYear.toInt())
                if (yearsElapsed < 3) {
                    // Early game: lower chance, depends on nation count
                    if (rng.nextDouble() > 0.3) return "랜덤임관"
                } else {
                    if (rng.nextDouble() < 0.5) return "랜덤임관"
                }
            }
            // 20% chance to move
            if (rng.nextDouble() < 0.2) return "이동"
        }

        // Neutral fallback
        return doNeutral(general, null, rng)
    }

    // ──────────────────────────────────────────────────────────
    //  Diplomacy state calculation
    // ──────────────────────────────────────────────────────────

    internal fun calcDiplomacyState(nation: Nation?, diplomacies: List<Diplomacy>): DiplomacyState {
        if (nation == null) return DiplomacyState.PEACE

        val relevant = diplomacies.filter {
            it.srcNationId == nation.id || it.destNationId == nation.id
        }

        if (relevant.any { it.stateCode == "선전포고" }) return DiplomacyState.AT_WAR
        if (nation.warState > 0) return DiplomacyState.AT_WAR

        if (relevant.any { it.stateCode == "종전제의" }) {
            val nationTroops = generalRepository.findByNationId(nation.id).sumOf { it.crew.toLong() }
            return if (nationTroops < 3000) DiplomacyState.RECRUITING else DiplomacyState.DECLARED
        }

        val allDiplomacies = diplomacies.filter { it.stateCode != "동맹" }
        val hostileNationIds = allDiplomacies
            .filter { it.srcNationId == nation.id || it.destNationId == nation.id }
            .map { if (it.srcNationId == nation.id) it.destNationId else it.srcNationId }
            .toSet()

        if (hostileNationIds.isNotEmpty()) {
            val adjacentCities = cityRepository.findByNationId(nation.id).filter { it.frontState > 0 }
            if (adjacentCities.isNotEmpty()) {
                val enemyTroops = hostileNationIds.sumOf { nid ->
                    generalRepository.findByNationId(nid).sumOf { it.crew.toLong() }
                }
                val ownTroops = generalRepository.findByNationId(nation.id).sumOf { it.crew.toLong() }
                if (enemyTroops > ownTroops * 2) return DiplomacyState.IMMINENT
            }
        }

        return DiplomacyState.PEACE
    }

    /**
     * Calculate war target nations map: nationId -> state (1=war ready, 2=at war).
     * Per legacy: state 0 (war) -> 2, state 1 with term<5 -> 1.
     */
    private fun calcWarTargetNations(nation: Nation?, diplomacies: List<Diplomacy>): Map<Long, Int> {
        if (nation == null) return emptyMap()
        val result = mutableMapOf<Long, Int>()
        val relevant = diplomacies.filter {
            it.srcNationId == nation.id || it.destNationId == nation.id
        }
        for (d in relevant) {
            val targetId = if (d.srcNationId == nation.id) d.destNationId else d.srcNationId
            when {
                d.stateCode == "선전포고" || d.stateCode == "전쟁" -> result[targetId] = 2
                d.stateCode == "불가침" -> { /* skip */ }
                else -> result.putIfAbsent(targetId, 1)
            }
        }
        if (result.isEmpty()) {
            result[0L] = 1 // Neutral targets
        }
        return result
    }

    // ──────────────────────────────────────────────────────────
    //  General type classification
    // ──────────────────────────────────────────────────────────

    internal fun classifyGeneral(
        general: General,
        rng: Random = Random(0),
        minNPCWarLeadership: Int = 40,
    ): Int {
        var flags = 0
        val l = general.leadership.toInt()
        val s = general.strength.toInt()
        val i = general.intel.toInt()

        if (s >= i) {
            flags = flags or GeneralType.WARRIOR.flag
            if (i > 0 && s > 0 && i.toDouble() / s >= 0.8 && rng.nextInt(100) < 50) {
                flags = flags or GeneralType.STRATEGIST.flag
            }
        }
        if (i > s) {
            flags = flags or GeneralType.STRATEGIST.flag
            if (s > 0 && i > 0 && s.toDouble() / i >= 0.8 && rng.nextInt(100) < 50) {
                flags = flags or GeneralType.WARRIOR.flag
            }
        }
        if (l >= minNPCWarLeadership) flags = flags or GeneralType.COMMANDER.flag
        return flags
    }

    // ──────────────────────────────────────────────────────────
    //  City development rate calculation (mirrors legacy calcCityDevelRate)
    // ──────────────────────────────────────────────────────────

    /**
     * Returns map of development key -> Pair(rate 0.0-1.0, generalTypeMask).
     */
    private fun calcCityDevelRate(city: City): Map<String, Pair<Double, Int>> {
        return mapOf(
            "trust" to Pair(city.trust.toDouble() / 100.0, GeneralType.COMMANDER.flag),
            "pop" to Pair(
                if (city.popMax > 0) city.pop.toDouble() / city.popMax else 1.0,
                GeneralType.COMMANDER.flag
            ),
            "agri" to Pair(
                if (city.agriMax > 0) city.agri.toDouble() / city.agriMax else 1.0,
                GeneralType.STRATEGIST.flag
            ),
            "comm" to Pair(
                if (city.commMax > 0) city.comm.toDouble() / city.commMax else 1.0,
                GeneralType.STRATEGIST.flag
            ),
            "secu" to Pair(
                if (city.secuMax > 0) city.secu.toDouble() / city.secuMax else 1.0,
                GeneralType.WARRIOR.flag
            ),
            "def" to Pair(
                if (city.defMax > 0) city.def.toDouble() / city.defMax else 1.0,
                GeneralType.WARRIOR.flag
            ),
            "wall" to Pair(
                if (city.wallMax > 0) city.wall.toDouble() / city.wallMax else 1.0,
                GeneralType.WARRIOR.flag
            ),
        )
    }

    /**
     * Overall development score for a city (0.0-1.0).
     */
    private fun calcCityDevScore(city: City): Double {
        val maxSum = (city.agriMax + city.commMax + city.secuMax + city.defMax + city.wallMax).toDouble()
        if (maxSum <= 0) return 1.0
        val curSum = (city.agri + city.comm + city.secu + city.def + city.wall).toDouble()
        return curSum / maxSum
    }

    // ──────────────────────────────────────────────────────────
    //  Chief (ruler) action decision
    // ──────────────────────────────────────────────────────────

    private fun decideChiefAction(
        ctx: AIContext,
        rng: Random,
        nationPolicy: NpcNationPolicy,
        generalPolicy: NpcGeneralPolicy,
        attackable: Boolean,
        warTargetNations: Map<Long, Int>,
        supplyCities: List<City>,
        backupCities: List<City>,
    ): String {
        val nation = ctx.nation ?: return "휴식"

        // Nation-level turn: iterate policy priorities
        for (priority in nationPolicy.priority) {
            if (!nationPolicy.canDo(priority)) continue
            val action = doNationAction(
                priority, ctx, rng, nationPolicy, supplyCities, backupCities, attackable, warTargetNations
            )
            if (action != null) return action
        }

        // Fall through to general turn
        return decideWarOrPeaceGeneralAction(ctx, rng, generalPolicy, nationPolicy, attackable, warTargetNations, supplyCities, backupCities)
    }

    /**
     * Route nation-level priority to the appropriate do*() logic.
     * Returns action string or null if conditions not met.
     */
    private fun doNationAction(
        priority: String,
        ctx: AIContext,
        rng: Random,
        nationPolicy: NpcNationPolicy,
        supplyCities: List<City>,
        backupCities: List<City>,
        attackable: Boolean,
        warTargetNations: Map<Long, Int>,
    ): String? {
        val nation = ctx.nation ?: return null
        val nationGenerals = ctx.nationGenerals
        val frontCities = ctx.frontCities

        return when (priority) {
            // ── 부대 발령 (troop assignment) ──
            "부대전방발령" -> doTroopFrontAssignment(ctx, rng, nationPolicy, frontCities, supplyCities)
            "부대후방발령" -> doTroopRearAssignment(ctx, rng, nationPolicy, backupCities, supplyCities)
            "부대구출발령" -> doTroopRescueAssignment(ctx, rng, nationPolicy, supplyCities)

            // ── NPC general assignment ──
            "NPC전방발령" -> doNpcFrontAssignment(ctx, rng, nationPolicy, frontCities, attackable)
            "NPC후방발령" -> doNpcRearAssignment(ctx, rng, nationPolicy, backupCities, supplyCities, frontCities)
            "NPC내정발령" -> doNpcDomesticAssignment(ctx, rng, nationPolicy, supplyCities)

            // ── User general assignment ──
            "유저장전방발령" -> doUserFrontAssignment(ctx, rng, nationPolicy, frontCities, attackable)
            "유저장후방발령" -> doUserRearAssignment(ctx, rng, nationPolicy, backupCities, supplyCities, frontCities)

            // ── Rewards ──
            "NPC포상" -> doNpcReward(ctx, rng, nationPolicy)
            "유저장포상" -> doUserReward(ctx, rng, nationPolicy)

            // ── Confiscation ──
            "NPC몰수" -> doNpcConfiscation(ctx, rng, nationPolicy)

            // ── Diplomacy ──
            "불가침제의" -> doNonAggressionProposal(ctx, rng, nationPolicy, supplyCities)
            "선전포고" -> doDeclaration(ctx, rng, nationPolicy, attackable, warTargetNations, supplyCities)

            // ── Capital move ──
            "천도" -> doMoveCapital(ctx, rng, nationPolicy, supplyCities)

            // ── Strategic war commands (used during war) ──
            "전시전략" -> {
                if (ctx.diplomacyState == DiplomacyState.AT_WAR && nation.strategicCmdLimit > 0) {
                    listOf("급습", "필사즉생", "의병모집")[rng.nextInt(3)]
                } else null
            }

            else -> null
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Nation-level do*() methods: Troop assignments
    // ──────────────────────────────────────────────────────────

    /**
     * 부대전방발령: Move troop leaders to front cities.
     * Per legacy: find troop leaders not at front, assign them to a front city.
     */
    private fun doTroopFrontAssignment(
        ctx: AIContext, rng: Random, policy: NpcNationPolicy,
        frontCities: List<City>, supplyCities: List<City>,
    ): String? {
        val nation = ctx.nation ?: return null
        if (nation.capitalCityId == null) return null
        if (frontCities.isEmpty()) return null

        val troopLeaders = ctx.nationGenerals.filter { it.npcState.toInt() == 5 }
        val frontCityIds = frontCities.map { it.id }.toSet()

        val candidates = troopLeaders.filter { leader ->
            !frontCityIds.contains(leader.cityId)
        }

        if (candidates.isEmpty()) return null

        // Pick a leader, assign to a front city
        val target = candidates[rng.nextInt(candidates.size)]
        val destCity = frontCities[rng.nextInt(frontCities.size)]

        // Store assignment in meta for the engine to execute
        target.meta["assignedCity"] = destCity.id
        return "발령"
    }

    /**
     * 부대후방발령: Move troop leaders that need recruitment to rear cities.
     */
    private fun doTroopRearAssignment(
        ctx: AIContext, rng: Random, policy: NpcNationPolicy,
        backupCities: List<City>, supplyCities: List<City>,
    ): String? {
        val nation = ctx.nation ?: return null
        if (nation.capitalCityId == null) return null

        val troopLeaders = ctx.nationGenerals.filter { it.npcState.toInt() == 5 }
        val supplyCityIds = supplyCities.map { it.id }.toSet()

        // Find troop leaders in cities with low population
        val candidates = troopLeaders.filter { leader ->
            val leaderCity = ctx.allCities.find { it.id == leader.cityId }
            if (leaderCity == null || !supplyCityIds.contains(leaderCity.id)) {
                true // Lost troop leader, needs rescue/rear
            } else {
                leaderCity.popMax > 0 &&
                    leaderCity.pop.toDouble() / leaderCity.popMax < policy.safeRecruitCityPopulationRatio
            }
        }

        if (candidates.isEmpty()) return null

        // Find suitable rear cities with enough population
        val recruitCities = (backupCities.ifEmpty { supplyCities }).filter { city ->
            city.popMax > 0 &&
                city.pop.toDouble() / city.popMax >= policy.safeRecruitCityPopulationRatio
        }

        if (recruitCities.isEmpty()) return null

        val target = candidates[rng.nextInt(candidates.size)]
        val destCity = recruitCities[rng.nextInt(recruitCities.size)]
        target.meta["assignedCity"] = destCity.id
        return "발령"
    }

    /**
     * 부대구출발령: Rescue troop leaders stuck in non-supply cities.
     */
    private fun doTroopRescueAssignment(
        ctx: AIContext, rng: Random, policy: NpcNationPolicy,
        supplyCities: List<City>,
    ): String? {
        val nation = ctx.nation ?: return null
        if (nation.capitalCityId == null) return null

        val supplyCityIds = supplyCities.map { it.id }.toSet()
        val troopLeaders = ctx.nationGenerals.filter { it.npcState.toInt() == 5 }

        // Find troop leaders in non-supply cities (lost/cut off)
        val lostLeaders = troopLeaders.filter { !supplyCityIds.contains(it.cityId) }
        if (lostLeaders.isEmpty()) return null
        if (supplyCities.isEmpty()) return null

        val target = lostLeaders[rng.nextInt(lostLeaders.size)]
        val destCity = supplyCities[rng.nextInt(supplyCities.size)]
        target.meta["assignedCity"] = destCity.id
        return "발령"
    }

    // ──────────────────────────────────────────────────────────
    //  Nation-level do*() methods: NPC general assignments
    // ──────────────────────────────────────────────────────────

    /**
     * NPC전방발령: Move war-ready NPC generals to front.
     * Per legacy: NPC war generals not at front, with sufficient crew/train/atmos.
     */
    private fun doNpcFrontAssignment(
        ctx: AIContext, rng: Random, policy: NpcNationPolicy,
        frontCities: List<City>, attackable: Boolean,
    ): String? {
        val nation = ctx.nation ?: return null
        if (nation.capitalCityId == null) return null
        if (frontCities.isEmpty()) return null
        if (ctx.diplomacyState == DiplomacyState.PEACE || ctx.diplomacyState == DiplomacyState.DECLARED) return null

        val frontCityIds = frontCities.map { it.id }.toSet()
        val nationCityIds = ctx.allCities.filter { it.nationId == nation.id }.map { it.id }.toSet()

        val npcWarGenerals = ctx.nationGenerals.filter { gen ->
            gen.npcState.toInt() >= 2 &&
                gen.npcState.toInt() != 5 &&
                gen.leadership >= policy.minNPCWarLeadership &&
                gen.id != ctx.general.id
        }

        val candidates = npcWarGenerals.filter { gen ->
            !frontCityIds.contains(gen.cityId) &&
                nationCityIds.contains(gen.cityId) &&
                gen.crew >= policy.minNPCWarLeadership && // minWarCrew analog
                gen.troopId == 0L &&
                max(gen.train.toInt(), gen.atmos.toInt()) >= 80
        }

        if (candidates.isEmpty()) return null

        // Weight front cities by importance (using officer count as proxy)
        val target = candidates[rng.nextInt(candidates.size)]
        val destCity = frontCities[rng.nextInt(frontCities.size)]
        target.meta["assignedCity"] = destCity.id
        return "발령"
    }

    /**
     * NPC후방발령: Move NPC war generals that need recruitment to rear.
     * Per legacy: NPC war generals at front with low crew, move to city with population.
     */
    private fun doNpcRearAssignment(
        ctx: AIContext, rng: Random, policy: NpcNationPolicy,
        backupCities: List<City>, supplyCities: List<City>, frontCities: List<City>,
    ): String? {
        val nation = ctx.nation ?: return null
        if (nation.capitalCityId == null) return null
        if (ctx.diplomacyState != DiplomacyState.AT_WAR) return null

        val supplyCityIds = supplyCities.map { it.id }.toSet()

        val npcWarGenerals = ctx.nationGenerals.filter { gen ->
            gen.npcState.toInt() >= 2 && gen.npcState.toInt() != 5 &&
                gen.leadership >= policy.minNPCWarLeadership &&
                gen.id != ctx.general.id &&
                gen.troopId == 0L
        }

        // Generals in supply cities with low population ratio, needing crew
        val candidates = npcWarGenerals.filter { gen ->
            if (!supplyCityIds.contains(gen.cityId)) return@filter false
            if (gen.crew >= 500) return@filter false // minWarCrew
            val genCity = ctx.allCities.find { it.id == gen.cityId } ?: return@filter false
            genCity.popMax > 0 &&
                genCity.pop.toDouble() / genCity.popMax < policy.safeRecruitCityPopulationRatio
        }

        if (candidates.isEmpty()) return null
        if (supplyCities.size <= 1) return null

        val minRecruitPop = policy.minNPCRecruitCityPopulation

        // Find cities with enough population for recruitment
        val recruitCities = (backupCities.ifEmpty { supplyCities }).filter { city ->
            city.pop >= minRecruitPop &&
                city.popMax > 0 &&
                city.pop.toDouble() / city.popMax >= policy.safeRecruitCityPopulationRatio
        }

        if (recruitCities.isEmpty()) return null

        val target = candidates[rng.nextInt(candidates.size)]
        val destCity = choiceByWeight(rng, recruitCities) { city ->
            city.pop.toDouble() / city.popMax
        } ?: return null
        target.meta["assignedCity"] = destCity.id
        return "발령"
    }

    /**
     * NPC내정발령: Move NPC civil generals to under-developed cities.
     * Per legacy: find generals in well-developed cities (dev>=0.95) and move to under-developed ones.
     */
    private fun doNpcDomesticAssignment(
        ctx: AIContext, rng: Random, policy: NpcNationPolicy,
        supplyCities: List<City>,
    ): String? {
        val nation = ctx.nation ?: return null
        if (nation.capitalCityId == null) return null
        if (supplyCities.size <= 1) return null

        val avgDev = supplyCities.map { calcCityDevScore(it) }.average()
        if (avgDev >= 0.99) return null

        val supplyCityMap = supplyCities.associateBy { it.id }

        // In peace, also include war NPC generals
        val npcGenerals = ctx.nationGenerals.filter { gen ->
            gen.npcState.toInt() >= 2 && gen.npcState.toInt() != 5 && gen.id != ctx.general.id
        }

        val civilGenerals = if (ctx.diplomacyState == DiplomacyState.PEACE || ctx.diplomacyState == DiplomacyState.DECLARED) {
            npcGenerals
        } else {
            npcGenerals.filter { gen -> gen.leadership < policy.minNPCWarLeadership }
        }

        // Find generals in well-developed cities
        val candidates = civilGenerals.filter { gen ->
            val city = supplyCityMap[gen.cityId] ?: return@filter false
            calcCityDevScore(city) >= 0.95
        }

        if (candidates.isEmpty()) return null

        // Weight under-developed cities by need
        val cityWeights = supplyCities.map { city ->
            val dev = min(calcCityDevScore(city), 0.999)
            val score = (1.0 - dev).pow(2.0)
            val generalCount = ctx.nationGenerals.count { it.cityId == city.id }
            city to score / sqrt(generalCount.toDouble() + 1.0)
        }.filter { it.second > 0.0 }

        if (cityWeights.isEmpty()) return null

        val destGeneral = candidates[rng.nextInt(candidates.size)]
        val srcCity = supplyCityMap[destGeneral.cityId]
        val destCity = choiceByWeightPair(rng, cityWeights) ?: return null

        // Don't move to a city that's already better developed
        if (srcCity != null && calcCityDevScore(srcCity) <= calcCityDevScore(destCity)) return null

        destGeneral.meta["assignedCity"] = destCity.id
        return "발령"
    }

    // ──────────────────────────────────────────────────────────
    //  Nation-level: User general assignments
    // ──────────────────────────────────────────────────────────

    /**
     * 유저장전방발령: Move user war generals to front.
     */
    private fun doUserFrontAssignment(
        ctx: AIContext, rng: Random, policy: NpcNationPolicy,
        frontCities: List<City>, attackable: Boolean,
    ): String? {
        val nation = ctx.nation ?: return null
        if (nation.capitalCityId == null) return null
        if (frontCities.isEmpty()) return null
        if (ctx.diplomacyState == DiplomacyState.PEACE || ctx.diplomacyState == DiplomacyState.DECLARED) return null

        val frontCityIds = frontCities.map { it.id }.toSet()
        val nationCityIds = ctx.allCities.filter { it.nationId == nation.id }.map { it.id }.toSet()

        val userWarGenerals = ctx.nationGenerals.filter { gen ->
            gen.npcState.toInt() < 2 && gen.id != ctx.general.id
        }

        val candidates = userWarGenerals.filter { gen ->
            nationCityIds.contains(gen.cityId) &&
                !frontCityIds.contains(gen.cityId) &&
                gen.crew >= 500 &&
                gen.troopId == 0L &&
                max(gen.train.toInt(), gen.atmos.toInt()) >= 80
        }

        if (candidates.isEmpty()) return null

        val target = candidates[rng.nextInt(candidates.size)]
        val destCity = frontCities[rng.nextInt(frontCities.size)]
        target.meta["assignedCity"] = destCity.id
        return "발령"
    }

    /**
     * 유저장후방발령: Move user war generals needing recruitment to rear.
     */
    private fun doUserRearAssignment(
        ctx: AIContext, rng: Random, policy: NpcNationPolicy,
        backupCities: List<City>, supplyCities: List<City>, frontCities: List<City>,
    ): String? {
        val nation = ctx.nation ?: return null
        if (nation.capitalCityId == null) return null
        if (ctx.diplomacyState != DiplomacyState.AT_WAR) return null

        val supplyCityMap = supplyCities.associateBy { it.id }

        val userWarGenerals = ctx.nationGenerals.filter { gen ->
            gen.npcState.toInt() < 2 && gen.id != ctx.general.id && gen.troopId == 0L
        }

        val candidates = userWarGenerals.filter { gen ->
            val city = supplyCityMap[gen.cityId] ?: return@filter false
            gen.crew < 500 &&
                city.popMax > 0 &&
                city.pop.toDouble() / city.popMax < policy.safeRecruitCityPopulationRatio
        }

        if (candidates.isEmpty()) return null
        if (supplyCities.size <= 1) return null

        val pickedGeneral = candidates[rng.nextInt(candidates.size)]
        val minRecruitPop = pickedGeneral.leadership.toInt() * 100 + policy.minNPCRecruitCityPopulation

        val recruitCities = (backupCities.ifEmpty { supplyCities }).filter { city ->
            city.pop >= minRecruitPop && city.popMax > 0 &&
                city.pop.toDouble() / city.popMax >= policy.safeRecruitCityPopulationRatio
        }

        if (recruitCities.isEmpty()) return null

        val destCity = choiceByWeight(rng, recruitCities) { city ->
            city.pop.toDouble() / city.popMax
        } ?: return null
        pickedGeneral.meta["assignedCity"] = destCity.id
        return "발령"
    }

    // ──────────────────────────────────────────────────────────
    //  Nation-level: Rewards (포상)
    // ──────────────────────────────────────────────────────────

    /**
     * NPC포상: Reward NPC generals that are low on resources.
     * Per legacy: compare general's gold/rice to required amounts, pay geometric mean.
     */
    private fun doNpcReward(ctx: AIContext, rng: Random, policy: NpcNationPolicy): String? {
        val nation = ctx.nation ?: return null
        if (nation.gold < policy.reqNationGold) return null
        if (nation.rice < policy.reqNationRice) return null

        val npcGenerals = ctx.nationGenerals.filter { gen ->
            gen.npcState.toInt() >= 2 && gen.npcState.toInt() != 5 &&
                gen.id != ctx.general.id &&
                (gen.killTurn?.toInt() ?: 100) > 5
        }

        if (npcGenerals.isEmpty()) return null

        // Find the NPC general most in need
        val candidates = mutableListOf<Pair<General, Double>>()

        for (gen in npcGenerals) {
            val isWarGen = gen.leadership >= policy.minNPCWarLeadership
            val reqGold = if (isWarGen) policy.reqNationGold else (policy.reqNationGold / 2)
            val reqRice = if (isWarGen) policy.reqNationRice else (policy.reqNationRice / 2)

            if (gen.gold < reqGold) {
                val deficit = (reqGold - gen.gold).toDouble()
                candidates.add(gen to deficit)
            } else if (gen.rice < reqRice) {
                val deficit = (reqRice - gen.rice).toDouble()
                candidates.add(gen to deficit)
            }
        }

        if (candidates.isEmpty()) return null

        // Pick highest-need general
        val (target, _) = candidates.maxByOrNull { it.second } ?: return null

        // Calculate payment amount: geometric mean of deficit and treasury
        val goldDeficit = max(0, policy.reqNationGold - target.gold)
        val riceDeficit = max(0, policy.reqNationRice - target.rice)

        val payGold = if (goldDeficit > riceDeficit) {
            valueFit(sqrt(goldDeficit.toDouble() * nation.gold).toInt(), policy.minimumResourceActionAmount, policy.maximumResourceActionAmount)
        } else 0

        val payRice = if (riceDeficit >= goldDeficit) {
            valueFit(sqrt(riceDeficit.toDouble() * nation.rice).toInt(), policy.minimumResourceActionAmount, policy.maximumResourceActionAmount)
        } else 0

        if (payGold <= 0 && payRice <= 0) return null

        // Store reward info
        target.meta["rewardGold"] = payGold
        target.meta["rewardRice"] = payRice
        return "포상"
    }

    /**
     * 유저장포상: Reward user generals low on resources.
     */
    private fun doUserReward(ctx: AIContext, rng: Random, policy: NpcNationPolicy): String? {
        val nation = ctx.nation ?: return null
        if (nation.gold < policy.reqNationGold) return null
        if (nation.rice < policy.reqNationRice) return null

        val userGenerals = ctx.nationGenerals.filter { gen ->
            gen.npcState.toInt() < 2 && gen.id != ctx.general.id &&
                (gen.killTurn?.toInt() ?: 100) > 5
        }

        if (userGenerals.isEmpty()) return null

        val candidates = mutableListOf<Pair<General, Double>>()
        val reqGold = policy.reqNationGold
        val reqRice = policy.reqNationRice

        for (gen in userGenerals) {
            if (gen.gold < reqGold) {
                candidates.add(gen to (reqGold - gen.gold).toDouble())
            } else if (gen.rice < reqRice) {
                candidates.add(gen to (reqRice - gen.rice).toDouble())
            }
        }

        if (candidates.isEmpty()) return null

        val (target, deficit) = candidates.maxByOrNull { it.second } ?: return null
        val payAmount = valueFit(
            sqrt(deficit * max(nation.gold, nation.rice).toDouble()).toInt(),
            policy.minimumResourceActionAmount,
            policy.maximumResourceActionAmount
        )
        if (payAmount <= 0) return null

        target.meta["rewardGold"] = if (target.gold < target.rice) payAmount else 0
        target.meta["rewardRice"] = if (target.rice <= target.gold) payAmount else 0
        return "포상"
    }

    // ──────────────────────────────────────────────────────────
    //  Nation-level: Confiscation (몰수)
    // ──────────────────────────────────────────────────────────

    /**
     * NPC몰수: Take resources from NPC generals who have excess.
     * Per legacy: civil NPCs with > 1.5x required, or war NPCs when treasury is low.
     */
    private fun doNpcConfiscation(ctx: AIContext, rng: Random, policy: NpcNationPolicy): String? {
        val nation = ctx.nation ?: return null

        val npcGenerals = ctx.nationGenerals.filter { gen ->
            gen.npcState.toInt() >= 2 && gen.npcState.toInt() != 5 && gen.id != ctx.general.id
        }
        if (npcGenerals.isEmpty()) return null

        val reqGold = policy.reqNationGold
        val reqRice = policy.reqNationRice

        data class ConfCandidate(val general: General, val isGold: Boolean, val amount: Int, val weight: Double)

        val candidates = mutableListOf<ConfCandidate>()

        // Civil NPC generals (low leadership = civil)
        val civilNpcs = npcGenerals.filter { it.leadership < policy.minNPCWarLeadership }
            .sortedByDescending { it.gold + it.rice }
        val warNpcs = npcGenerals.filter { it.leadership >= policy.minNPCWarLeadership }
            .sortedByDescending { it.gold + it.rice }

        val reqDevelGold = reqGold / 2
        val reqDevelRice = reqRice / 2

        for (gen in civilNpcs) {
            if (gen.gold > reqDevelGold * 1.5) {
                val take = valueFit((gen.gold - reqDevelGold * 1.2).toInt(), 100, policy.maximumResourceActionAmount)
                if (take >= policy.minimumResourceActionAmount) {
                    candidates.add(ConfCandidate(gen, true, take, take.toDouble()))
                }
            }
            if (gen.rice > reqDevelRice * 1.5) {
                val take = valueFit((gen.rice - reqDevelRice * 1.2).toInt(), 100, policy.maximumResourceActionAmount)
                if (take >= policy.minimumResourceActionAmount) {
                    candidates.add(ConfCandidate(gen, false, take, take.toDouble()))
                }
            }
        }

        // War NPCs: only when treasury needs it
        val goldDeficit = reqGold * 1.5 - nation.gold
        val riceDeficit = reqRice * 1.5 - nation.rice

        if (goldDeficit > 0) {
            for (gen in warNpcs) {
                if (gen.gold <= reqGold) continue
                val take = valueFit(
                    sqrt((gen.gold - reqGold).toDouble() * goldDeficit).toInt(),
                    100, policy.maximumResourceActionAmount
                )
                if (take >= policy.minimumResourceActionAmount) {
                    candidates.add(ConfCandidate(gen, true, take, take.toDouble()))
                }
            }
        }
        if (riceDeficit > 0) {
            for (gen in warNpcs) {
                if (gen.rice <= reqRice) continue
                val take = valueFit(
                    sqrt((gen.rice - reqRice).toDouble() * riceDeficit).toInt(),
                    100, policy.maximumResourceActionAmount
                )
                if (take >= policy.minimumResourceActionAmount) {
                    candidates.add(ConfCandidate(gen, false, take, take.toDouble()))
                }
            }
        }

        if (candidates.isEmpty()) return null

        // Pick by weight
        val picked = choiceByWeightPairRaw(rng, candidates.map { it to it.weight }) ?: return null
        picked.general.meta["confiscateGold"] = if (picked.isGold) picked.amount else 0
        picked.general.meta["confiscateRice"] = if (!picked.isGold) picked.amount else 0
        return "몰수"
    }

    // ──────────────────────────────────────────────────────────
    //  Nation-level: Diplomacy
    // ──────────────────────────────────────────────────────────

    /**
     * 불가침제의: Propose non-aggression pact.
     * Per legacy: look for nations that have assisted, propose treaty.
     */
    private fun doNonAggressionProposal(
        ctx: AIContext, rng: Random, policy: NpcNationPolicy, supplyCities: List<City>,
    ): String? {
        val nation = ctx.nation ?: return null
        if (ctx.general.officerLevel < 12) return null
        if (supplyCities.isEmpty()) return null

        // Check for potential allies among non-hostile neighbors
        val otherNations = ctx.allNations.filter {
            it.id != nation.id && it.level > 0
        }
        if (otherNations.isEmpty()) return null

        // Find nations we're not at war with
        val diplomacies = diplomacyRepository.findByWorldIdAndIsDeadFalse(ctx.world.id.toLong())
        val hostileIds = diplomacies.filter {
            (it.srcNationId == nation.id || it.destNationId == nation.id) &&
                (it.stateCode == "선전포고" || it.stateCode == "전쟁")
        }.map { if (it.srcNationId == nation.id) it.destNationId else it.srcNationId }.toSet()

        val alreadyPact = diplomacies.filter {
            (it.srcNationId == nation.id || it.destNationId == nation.id) &&
                it.stateCode == "불가침"
        }.map { if (it.srcNationId == nation.id) it.destNationId else it.srcNationId }.toSet()

        val candidates = otherNations.filter {
            !hostileIds.contains(it.id) && !alreadyPact.contains(it.id) && it.power < nation.power * 2
        }

        if (candidates.isEmpty()) return null

        // Only propose with some probability
        if (rng.nextDouble() > 0.15) return null

        val target = candidates[rng.nextInt(candidates.size)]
        ctx.general.meta["diplomacyTarget"] = target.id
        return "불가침제의"
    }

    /**
     * 선전포고: Declare war on a neighbor.
     * Per legacy: complex resource/development check, then pick weakest neighbor.
     */
    private fun doDeclaration(
        ctx: AIContext, rng: Random, policy: NpcNationPolicy,
        attackable: Boolean, warTargetNations: Map<Long, Int>, supplyCities: List<City>,
    ): String? {
        val nation = ctx.nation ?: return null
        if (ctx.general.officerLevel < 12) return null
        if (ctx.diplomacyState != DiplomacyState.PEACE) return null
        if (attackable) return null
        if (nation.capitalCityId == null) return null
        if (ctx.frontCities.isNotEmpty()) return null

        // Check tech readiness (per legacy: need sufficient tech)
        // Check resource readiness
        val npcGenerals = ctx.nationGenerals.filter { it.npcState.toInt() >= 2 && it.npcState.toInt() != 5 }
        val userGenerals = ctx.nationGenerals.filter { it.npcState.toInt() < 2 }

        if (npcGenerals.isEmpty() && userGenerals.isEmpty()) return null

        var avgGold = nation.gold.toDouble()
        var avgRice = nation.rice.toDouble()
        var genCnt = 1

        for (gen in npcGenerals) {
            avgGold += gen.gold
            avgRice += gen.rice
            genCnt++
        }
        for (gen in userGenerals) {
            avgGold += gen.gold / 2.0
            avgRice += gen.rice / 2.0
            genCnt++
        }

        avgGold /= genCnt
        avgRice /= genCnt

        // Calculate trial probability based on resources and development
        var trialProp = avgGold / max(policy.reqNationGold * 1.5, 2000.0)
        trialProp += avgRice / max(policy.reqNationRice * 1.5, 2000.0)

        if (supplyCities.isNotEmpty()) {
            val devRates = supplyCities.map { calcCityDevScore(it) }
            val popRates = supplyCities.map {
                if (it.popMax > 0) it.pop.toDouble() / it.popMax else 0.0
            }
            trialProp += (popRates.average() + devRates.average()) / 2.0
        }

        trialProp /= 4.0
        trialProp = trialProp.pow(6.0)

        if (rng.nextDouble() >= trialProp) return null

        // Find neighboring nations to declare war on
        // Per legacy: prefer nations not already in wars, weighted by inverse power
        val otherNations = ctx.allNations.filter {
            it.id != nation.id && it.level > 0
        }

        if (otherNations.isEmpty()) return null

        // Simple neighbor check: nations that share border (have cities adjacent to ours)
        val nationCityIds = ctx.allCities.filter { it.nationId == nation.id }.map { it.id }.toSet()
        val neighborNationIds = mutableSetOf<Long>()
        // Simplified: any nation that has cities in proximity
        for (city in ctx.allCities) {
            if (city.nationId != nation.id && city.nationId != 0L && city.frontState > 0) {
                neighborNationIds.add(city.nationId)
            }
        }
        // Also consider all other nations as potential targets
        if (neighborNationIds.isEmpty()) {
            neighborNationIds.addAll(otherNations.map { it.id })
        }

        val targets = otherNations.filter { neighborNationIds.contains(it.id) }
        if (targets.isEmpty()) return null

        // Weight by inverse power (prefer weaker targets)
        val target = choiceByWeight(rng, targets) { 1.0 / sqrt(it.power.toDouble() + 1.0) } ?: return null
        ctx.general.meta["warTarget"] = target.id
        return "선전포고"
    }

    /**
     * 천도: Move capital to a better city.
     * Per legacy: score = pop * (maxDistSum / cityDistSum) * sqrt(dev), move if capital isn't in top 25%.
     */
    private fun doMoveCapital(
        ctx: AIContext, rng: Random, policy: NpcNationPolicy, supplyCities: List<City>,
    ): String? {
        val nation = ctx.nation ?: return null
        val capital = nation.capitalCityId ?: return null
        if (supplyCities.size <= 1) return null

        // Score each supply city
        val cityScores = supplyCities.map { city ->
            val dev = calcCityDevScore(city)
            val score = city.pop.toDouble() * sqrt(dev)
            city to score
        }.sortedByDescending { it.second }

        // Check if capital is already in top 25%
        val top25Limit = (cityScores.size * 0.25).toInt().coerceAtLeast(1)
        val capitalRank = cityScores.indexOfFirst { it.first.id == capital }
        if (capitalRank in 0 until top25Limit) return null

        // Best city
        val bestCity = cityScores.firstOrNull()?.first ?: return null
        if (bestCity.id == capital) return null

        ctx.general.meta["capitalTarget"] = bestCity.id
        return "천도"
    }

    // ──────────────────────────────────────────────────────────
    //  General-level action decision
    // ──────────────────────────────────────────────────────────

    private fun decideWarOrPeaceGeneralAction(
        ctx: AIContext, rng: Random, generalPolicy: NpcGeneralPolicy,
        nationPolicy: NpcNationPolicy, attackable: Boolean,
        warTargetNations: Map<Long, Int>, supplyCities: List<City>, backupCities: List<City>,
    ): String {
        return if (ctx.diplomacyState == DiplomacyState.AT_WAR ||
            ctx.diplomacyState == DiplomacyState.RECRUITING ||
            ctx.diplomacyState == DiplomacyState.IMMINENT
        ) {
            decideWarAction(ctx, rng, generalPolicy, nationPolicy, attackable, warTargetNations, supplyCities, backupCities)
        } else {
            decidePeaceAction(ctx, rng, generalPolicy, nationPolicy, supplyCities, backupCities)
        }
    }

    // ──────────────────────────────────────────────────────────
    //  War-time general actions
    // ──────────────────────────────────────────────────────────

    private fun decideWarAction(
        ctx: AIContext, rng: Random, policy: NpcGeneralPolicy,
        nationPolicy: NpcNationPolicy, attackable: Boolean,
        warTargetNations: Map<Long, Int>, supplyCities: List<City>, backupCities: List<City>,
    ): String {
        val general = ctx.general
        val city = ctx.city

        if (general.injury > 0) return "요양"

        // Iterate general policy priorities
        for (priority in policy.priority) {
            if (!policy.canDo(priority)) continue
            val action = doGeneralAction(
                priority, ctx, rng, policy, nationPolicy, true, attackable, warTargetNations, supplyCities, backupCities
            )
            if (action != null) return action
        }

        // Fallback: neutral action
        return doNeutral(general, ctx.nation, rng)
    }

    // ──────────────────────────────────────────────────────────
    //  Peace-time general actions
    // ──────────────────────────────────────────────────────────

    private fun decidePeaceAction(
        ctx: AIContext, rng: Random, policy: NpcGeneralPolicy,
        nationPolicy: NpcNationPolicy, supplyCities: List<City>, backupCities: List<City>,
    ): String {
        val general = ctx.general
        val city = ctx.city

        if (general.injury > 0) return "요양"

        // Iterate general policy priorities
        for (priority in policy.priority) {
            if (!policy.canDo(priority)) continue
            val action = doGeneralAction(
                priority, ctx, rng, policy, nationPolicy, false, false, emptyMap(), supplyCities, backupCities
            )
            if (action != null) return action
        }

        // Fallback: neutral action
        return doNeutral(general, ctx.nation, rng)
    }

    /**
     * Route general-level priority to the appropriate do*() logic.
     */
    private fun doGeneralAction(
        priority: String,
        ctx: AIContext, rng: Random,
        policy: NpcGeneralPolicy, nationPolicy: NpcNationPolicy,
        warMode: Boolean, attackable: Boolean,
        warTargetNations: Map<Long, Int>,
        supplyCities: List<City>, backupCities: List<City>,
    ): String? {
        return when (priority) {
            "긴급내정" -> doUrgentDomestic(ctx, rng, nationPolicy)
            "전쟁내정" -> doWarDomestic(ctx, rng, nationPolicy)
            "징병" -> doRecruit(ctx, rng, policy, nationPolicy)
            "전투준비" -> doCombatPrep(ctx, rng, nationPolicy)
            "출병" -> doSortie(ctx, rng, nationPolicy, attackable, warTargetNations)
            "전방워프" -> doWarpToFront(ctx, rng, nationPolicy, attackable)
            "후방워프" -> doWarpToRear(ctx, rng, policy, nationPolicy, backupCities, supplyCities)
            "내정워프" -> doWarpToDomestic(ctx, rng, nationPolicy, supplyCities)
            "귀환" -> doReturn(ctx, rng)
            "일반내정" -> doNormalDomestic(ctx, rng, nationPolicy)
            "금쌀구매" -> doTradeResources(ctx, rng, nationPolicy)
            "NPC헌납" -> doDonate(ctx, rng, nationPolicy)
            "소집해제" -> doDismiss(ctx, rng, attackable)
            "중립" -> doNeutral(ctx.general, ctx.nation, rng)
            else -> null
        }
    }

    // ──────────────────────────────────────────────────────────
    //  do일반내정: Normal domestic development
    // ──────────────────────────────────────────────────────────

    /**
     * Per legacy: weighted random choice among development actions based on general type and city needs.
     */
    private fun doNormalDomestic(ctx: AIContext, rng: Random, nationPolicy: NpcNationPolicy): String? {
        val general = ctx.general
        val city = ctx.city
        val nation = ctx.nation ?: return null
        val genType = ctx.generalType

        // Per legacy: if nation rice is low, 30% chance to skip
        if (nation.rice < 1000 && rng.nextDouble() < 0.3) return null

        val develRate = calcCityDevelRate(city)
        val isSpringSummer = ctx.world.currentMonth <= 6

        data class WeightedAction(val action: String, val weight: Double)
        val cmdList = mutableListOf<WeightedAction>()

        val leadership = general.leadership.toInt()
        val strength = general.strength.toInt()
        val intel = general.intel.toInt()

        // Commander type: trust and population
        if (genType and GeneralType.COMMANDER.flag != 0) {
            val trustRate = develRate["trust"]!!.first
            if (trustRate < 0.98) {
                val w = leadership / valueFitD(trustRate / 2.0 - 0.2, 0.001) * 2.0
                cmdList.add(WeightedAction("주민선정", w))
            }
            val popRate = develRate["pop"]!!.first
            if (popRate < 0.8) {
                cmdList.add(WeightedAction("정착장려", leadership / valueFitD(popRate, 0.001)))
            } else if (popRate < 0.99) {
                cmdList.add(WeightedAction("정착장려", leadership / valueFitD(popRate / 4.0, 0.001)))
            }
        }

        // Warrior type: defense, wall, security
        if (genType and GeneralType.WARRIOR.flag != 0) {
            val defRate = develRate["def"]!!.first
            if (defRate < 1.0) {
                cmdList.add(WeightedAction("수비강화", strength / valueFitD(defRate, 0.001)))
            }
            val wallRate = develRate["wall"]!!.first
            if (wallRate < 1.0) {
                cmdList.add(WeightedAction("성벽보수", strength / valueFitD(wallRate, 0.001)))
            }
            val secuRate = develRate["secu"]!!.first
            if (secuRate < 0.9) {
                cmdList.add(WeightedAction("치안강화", strength / valueFitD(secuRate / 0.8, 0.001, 1.0)))
            } else if (secuRate < 1.0) {
                cmdList.add(WeightedAction("치안강화", strength / 2.0 / valueFitD(secuRate, 0.001)))
            }
        }

        // Strategist type: tech, agriculture, commerce
        if (genType and GeneralType.STRATEGIST.flag != 0) {
            // Tech research (simplified tech limit check)
            val techLevel = nation.tech.toInt()
            val yearsElapsed = ctx.world.currentYear - ((ctx.world.config["startyear"] as? Number)?.toInt() ?: ctx.world.currentYear.toInt())
            if (techLevel < yearsElapsed * 500) { // Simplified TechLimit
                val nextTech = techLevel % 1000 + 1
                if (techLevel + 1000 < yearsElapsed * 500) {
                    cmdList.add(WeightedAction("기술연구", intel / (nextTech / 2000.0)))
                } else {
                    cmdList.add(WeightedAction("기술연구", intel.toDouble()))
                }
            }

            val agriRate = develRate["agri"]!!.first
            if (agriRate < 1.0) {
                val seasonMod = if (isSpringSummer) 1.2 else 0.8
                cmdList.add(WeightedAction("농지개간", seasonMod * intel / valueFitD(agriRate, 0.001, 1.0)))
            }
            val commRate = develRate["comm"]!!.first
            if (commRate < 1.0) {
                val seasonMod = if (isSpringSummer) 0.8 else 1.2
                cmdList.add(WeightedAction("상업투자", seasonMod * intel / valueFitD(commRate, 0.001, 1.0)))
            }
        }

        if (cmdList.isEmpty()) return null

        return choiceByWeightPairRaw(rng, cmdList.map { it.action to it.weight })
    }

    // ──────────────────────────────────────────────────────────
    //  do긴급내정: Urgent domestic (during war)
    // ──────────────────────────────────────────────────────────

    private fun doUrgentDomestic(ctx: AIContext, rng: Random, nationPolicy: NpcNationPolicy): String? {
        if (ctx.diplomacyState == DiplomacyState.PEACE) return null

        val general = ctx.general
        val city = ctx.city
        val leadership = general.leadership.toInt()

        // Per legacy: trust < 70 -> 주민선정 with probability based on leadership
        if (city.trust < 70 && rng.nextDouble() < leadership.toDouble() / 60.0) {
            return "주민선정"
        }

        // Population too low for recruitment
        if (city.pop < nationPolicy.minNPCRecruitCityPopulation && rng.nextDouble() < leadership.toDouble() / 120.0) {
            return "정착장려"
        }

        return null
    }

    // ──────────────────────────────────────────────────────────
    //  do전쟁내정: Wartime domestic (reduced thresholds)
    // ──────────────────────────────────────────────────────────

    private fun doWarDomestic(ctx: AIContext, rng: Random, nationPolicy: NpcNationPolicy): String? {
        if (ctx.diplomacyState == DiplomacyState.PEACE) return null

        val general = ctx.general
        val city = ctx.city
        val nation = ctx.nation ?: return null
        val genType = ctx.generalType

        if (nation.rice < 1000 && rng.nextDouble() < 0.3) return null
        if (rng.nextDouble() < 0.3) return null  // 30% skip per legacy

        val develRate = calcCityDevelRate(city)
        val isSpringSummer = ctx.world.currentMonth <= 6
        val isFront = city.frontState.toInt() in listOf(1, 3)

        val leadership = general.leadership.toInt()
        val strength = general.strength.toInt()
        val intel = general.intel.toInt()

        data class WA(val action: String, val weight: Double)
        val cmdList = mutableListOf<WA>()

        // Commander: trust and pop (same as normal but lower thresholds)
        if (genType and GeneralType.COMMANDER.flag != 0) {
            val trustRate = develRate["trust"]!!.first
            if (trustRate < 0.98) {
                cmdList.add(WA("주민선정", leadership / valueFitD(trustRate / 2.0 - 0.2, 0.001) * 2.0))
            }
            val popRate = develRate["pop"]!!.first
            if (popRate < 0.8) {
                val divisor = if (isFront) 1.0 else 2.0
                cmdList.add(WA("정착장려", leadership / valueFitD(popRate, 0.001) / divisor))
            }
        }

        // Warrior: only if below 50%
        if (genType and GeneralType.WARRIOR.flag != 0) {
            val defRate = develRate["def"]!!.first
            if (defRate < 0.5) {
                cmdList.add(WA("수비강화", strength / valueFitD(defRate, 0.001) / 2.0))
            }
            val wallRate = develRate["wall"]!!.first
            if (wallRate < 0.5) {
                cmdList.add(WA("성벽보수", strength / valueFitD(wallRate, 0.001) / 2.0))
            }
            val secuRate = develRate["secu"]!!.first
            if (secuRate < 0.5) {
                cmdList.add(WA("치안강화", strength / valueFitD(secuRate / 0.8, 0.001, 1.0) / 4.0))
            }
        }

        // Strategist: only if below 50%
        if (genType and GeneralType.STRATEGIST.flag != 0) {
            val techLevel = nation.tech.toInt()
            val yearsElapsed = ctx.world.currentYear - ((ctx.world.config["startyear"] as? Number)?.toInt() ?: ctx.world.currentYear.toInt())
            if (techLevel < yearsElapsed * 500) {
                val nextTech = techLevel % 1000 + 1
                if (techLevel + 1000 < yearsElapsed * 500) {
                    cmdList.add(WA("기술연구", intel / (nextTech / 3000.0)))
                } else {
                    cmdList.add(WA("기술연구", intel.toDouble()))
                }
            }

            val agriRate = develRate["agri"]!!.first
            if (agriRate < 0.5) {
                val seasonMod = if (isSpringSummer) 1.2 else 0.8
                val frontDiv = if (isFront) 4.0 else 2.0
                cmdList.add(WA("농지개간", seasonMod * intel / frontDiv / valueFitD(agriRate, 0.001, 1.0)))
            }
            val commRate = develRate["comm"]!!.first
            if (commRate < 0.5) {
                val seasonMod = if (isSpringSummer) 0.8 else 1.2
                val frontDiv = if (isFront) 4.0 else 2.0
                cmdList.add(WA("상업투자", seasonMod * intel / frontDiv / valueFitD(commRate, 0.001, 1.0)))
            }
        }

        if (cmdList.isEmpty()) return null
        return choiceByWeightPairRaw(rng, cmdList.map { it.action to it.weight })
    }

    // ──────────────────────────────────────────────────────────
    //  do징병: Recruitment
    // ──────────────────────────────────────────────────────────

    /**
     * Per legacy: only during war/imminent, only COMMANDER type, check population safety.
     * Choose arm type based on dexterity weights, 모병 if rich, 징병 otherwise.
     */
    private fun doRecruit(
        ctx: AIContext, rng: Random, policy: NpcGeneralPolicy, nationPolicy: NpcNationPolicy,
    ): String? {
        // Only recruit during war preparation or war
        if (ctx.diplomacyState == DiplomacyState.PEACE || ctx.diplomacyState == DiplomacyState.DECLARED) return null

        // Only commanders recruit
        if (ctx.generalType and GeneralType.COMMANDER.flag == 0) return null

        val general = ctx.general
        val city = ctx.city

        // Already have enough crew
        if (general.crew >= policy.minWarCrew) return null

        // Population safety check
        val remainPop = city.pop - nationPolicy.minNPCRecruitCityPopulation - general.leadership.toInt() * 100
        if (remainPop <= 0) return null

        if (city.popMax > 0 &&
            city.pop.toDouble() / city.popMax < nationPolicy.safeRecruitCityPopulationRatio
        ) {
            val maxPop = city.popMax - nationPolicy.minNPCRecruitCityPopulation
            if (maxPop > 0 && rng.nextDouble() < remainPop.toDouble() / maxPop) {
                return null  // Probabilistically skip to protect population
            }
        }

        // Resource check: need gold for training after recruitment
        val gold = general.gold - general.leadership * 3  // Reserve for training
        val rice = general.rice - general.leadership * 4

        if (gold <= 0 || rice <= 0) return null

        // Choose 모병 (volunteer) if wealthy, 징병 (conscription) otherwise
        return if (general.gold > 3000) "모병" else "징병"
    }

    // ──────────────────────────────────────────────────────────
    //  do전투준비: Combat preparation (train/morale)
    // ──────────────────────────────────────────────────────────

    private fun doCombatPrep(ctx: AIContext, rng: Random, nationPolicy: NpcNationPolicy): String? {
        if (ctx.diplomacyState == DiplomacyState.PEACE || ctx.diplomacyState == DiplomacyState.DECLARED) return null

        val general = ctx.general
        if (general.crew <= 0) return null

        val train = general.train.toInt()
        val atmos = general.atmos.toInt()
        val threshold = 80 // nationPolicy.properWarTrainAtmos equivalent

        data class WA(val action: String, val weight: Double)
        val cmdList = mutableListOf<WA>()

        if (train < threshold) {
            cmdList.add(WA("훈련", 100.0 / valueFitD(train.toDouble(), 1.0)))
        }
        if (atmos < threshold) {
            cmdList.add(WA("사기진작", 100.0 / valueFitD(atmos.toDouble(), 1.0)))
        }

        if (cmdList.isEmpty()) return null
        return choiceByWeightPairRaw(rng, cmdList.map { it.action to it.weight })
    }

    // ──────────────────────────────────────────────────────────
    //  do출병: Sortie (attack)
    // ──────────────────────────────────────────────────────────

    /**
     * Per legacy: only during actual war, need sufficient crew/train/atmos,
     * must be in a front city with attackable neighbors.
     */
    private fun doSortie(
        ctx: AIContext, rng: Random, nationPolicy: NpcNationPolicy,
        attackable: Boolean, warTargetNations: Map<Long, Int>,
    ): String? {
        if (!attackable) return null
        if (ctx.diplomacyState != DiplomacyState.AT_WAR) return null

        val general = ctx.general
        val city = ctx.city
        val nation = ctx.nation ?: return null

        // Per legacy: if rice is very low and NPC, 70% chance to skip
        if (nation.rice < 1000 && general.npcState.toInt() >= 2 && rng.nextDouble() < 0.7) return null

        // Need minimum train and atmos
        if (general.train < min(100, 80)) return null
        if (general.atmos < min(100, 80)) return null
        // Need minimum crew
        if (general.crew < min((general.leadership.toInt() - 2) * 100, 500)) return null

        // Must be in a front city
        if (city.frontState.toInt() == 0) return null
        // Per legacy: front==1 means border but can't attack from there
        if (city.frontState.toInt() == 1) return null

        // Has attackable enemy neighbors - engine will pick target
        return "출병"
    }

    // ──────────────────────────────────────────────────────────
    //  do전방워프: Warp to front (NPC teleport)
    // ──────────────────────────────────────────────────────────

    private fun doWarpToFront(ctx: AIContext, rng: Random, nationPolicy: NpcNationPolicy, attackable: Boolean): String? {
        if (!attackable) return null
        if (ctx.diplomacyState == DiplomacyState.PEACE || ctx.diplomacyState == DiplomacyState.DECLARED) return null

        // Only commanders
        if (ctx.generalType and GeneralType.COMMANDER.flag == 0) return null

        val general = ctx.general
        if (general.crew < 500) return null  // minWarCrew

        // Already at front
        if (ctx.city.frontState > 0) return null

        // Must have front cities to go to
        if (ctx.frontCities.isEmpty()) return null

        // Pick a front city with supply
        val supplyFront = ctx.frontCities.filter { it.supplyState > 0 }
        if (supplyFront.isEmpty()) return null

        val destCity = supplyFront[rng.nextInt(supplyFront.size)]
        general.meta["warpTarget"] = destCity.id
        return "이동"  // NPC능동/순간이동 maps to 이동 in Kotlin engine
    }

    // ──────────────────────────────────────────────────────────
    //  do후방워프: Warp to rear for recruitment
    // ──────────────────────────────────────────────────────────

    private fun doWarpToRear(
        ctx: AIContext, rng: Random, policy: NpcGeneralPolicy,
        nationPolicy: NpcNationPolicy,
        backupCities: List<City>, supplyCities: List<City>,
    ): String? {
        if (ctx.diplomacyState == DiplomacyState.PEACE || ctx.diplomacyState == DiplomacyState.DECLARED) return null

        // Only commanders
        if (ctx.generalType and GeneralType.COMMANDER.flag == 0) return null

        val general = ctx.general
        // Already have enough crew
        if (general.crew >= 500) return null  // minWarCrew

        // Check if current city has enough population
        val city = ctx.city
        val minRecruitPop = general.leadership.toInt() * 100 + nationPolicy.minNPCRecruitCityPopulation

        if (city.popMax > 0 && city.pop.toDouble() / city.popMax >= nationPolicy.safeRecruitCityPopulationRatio &&
            city.pop >= minRecruitPop
        ) {
            return null  // Current city is fine for recruitment
        }

        // Find recruitable rear city
        val recruitCities = (backupCities.ifEmpty { supplyCities }).filter { c ->
            c.id != city.id &&
                c.pop >= minRecruitPop &&
                c.popMax > 0 &&
                c.pop.toDouble() / c.popMax >= nationPolicy.safeRecruitCityPopulationRatio
        }

        if (recruitCities.isEmpty()) return null

        val destCity = choiceByWeight(rng, recruitCities) { c ->
            c.pop.toDouble() / c.popMax
        } ?: return null

        general.meta["warpTarget"] = destCity.id
        return "이동"
    }

    // ──────────────────────────────────────────────────────────
    //  do내정워프: Warp to under-developed city for domestic work
    // ──────────────────────────────────────────────────────────

    private fun doWarpToDomestic(
        ctx: AIContext, rng: Random, nationPolicy: NpcNationPolicy,
        supplyCities: List<City>,
    ): String? {
        // Commanders during war don't do domestic warp
        if (ctx.generalType and GeneralType.COMMANDER.flag != 0 &&
            ctx.diplomacyState in listOf(DiplomacyState.AT_WAR, DiplomacyState.IMMINENT, DiplomacyState.RECRUITING)
        ) {
            return null
        }

        // Per legacy: 60% chance to skip
        if (rng.nextDouble() < 0.6) return null

        val city = ctx.city
        val genType = ctx.generalType
        val develRate = calcCityDevelRate(city)

        // Check how much this general can contribute to current city
        var availableTypeCnt = 0
        var warpProp = 1.0
        for ((_, pair) in develRate) {
            val (rate, typeFlag) = pair
            if (genType and typeFlag == 0) continue
            warpProp *= rate
            availableTypeCnt++
        }

        if (availableTypeCnt == 0) return null

        // If current city is well-developed for this general's type, probability is high -> skip warp
        if (rng.nextDouble() >= warpProp) {
            // Current city needs work, don't warp
            return null
        }

        // Find candidate cities that need development
        val candidates = supplyCities.filter { c ->
            if (c.id == city.id) return@filter false
            val cRate = calcCityDevelRate(c)
            var realRate = 0.0
            var cnt = 0
            for ((key, pair) in cRate) {
                if (genType and pair.second == 0) continue
                realRate += pair.first
                cnt++
            }
            if (cnt > 0) realRate /= cnt
            realRate < 0.95
        }

        if (candidates.isEmpty()) return null

        val destCity = candidates[rng.nextInt(candidates.size)]
        ctx.general.meta["warpTarget"] = destCity.id
        return "이동"
    }

    // ──────────────────────────────────────────────────────────
    //  do귀환: Return to own territory
    // ──────────────────────────────────────────────────────────

    private fun doReturn(ctx: AIContext, rng: Random): String? {
        val general = ctx.general
        val city = ctx.city

        // If in own territory with supply, no need to return
        if (city.nationId == general.nationId && city.supplyState > 0) return null

        return "귀환"
    }

    // ──────────────────────────────────────────────────────────
    //  do금쌀구매: Trade gold/rice
    // ──────────────────────────────────────────────────────────

    /**
     * Per legacy: balance gold and rice. If one is much more than the other, trade.
     * Considers kill/death ratio for rice cost estimation.
     */
    private fun doTradeResources(ctx: AIContext, rng: Random, nationPolicy: NpcNationPolicy): String? {
        val general = ctx.general

        // Need some baseline resources
        val totalRes = general.gold + general.rice
        if (totalRes < 2000) return null  // baseDevelCost*2

        val absGold = general.gold.toDouble()
        val absRice = general.rice.toDouble()

        // Per legacy: weight rice by kill/death ratio (more deaths = rice more expensive)
        val deathRate = 1.0  // Simplified; legacy uses kill/death stats

        val relGold = absGold
        val relRice = absRice * deathRate

        // Buy rice if gold >> rice
        if (relRice * 2.0 < relGold && relRice < 2000) {
            val amount = valueFit(((relGold - relRice) / (1.0 + deathRate)).toInt(), 100, 50000)
            if (amount >= nationPolicy.minimumResourceActionAmount) {
                general.meta["tradeDirection"] = "buyRice"
                general.meta["tradeAmount"] = amount
                return "군량매매"
            }
        }

        // Sell rice if rice >> gold
        if (relGold * 2.0 < relRice && relGold < 2000) {
            val amount = valueFit(((relRice - relGold) / (1.0 + deathRate)).toInt(), 100, 50000)
            if (amount >= nationPolicy.minimumResourceActionAmount) {
                general.meta["tradeDirection"] = "sellRice"
                general.meta["tradeAmount"] = amount
                return "군량매매"
            }
        }

        return null
    }

    // ──────────────────────────────────────────────────────────
    //  doNPC헌납: Donate resources to nation
    // ──────────────────────────────────────────────────────────

    /**
     * Per legacy: donate excess resources to nation treasury when nation is poor.
     */
    private fun doDonate(ctx: AIContext, rng: Random, nationPolicy: NpcNationPolicy): String? {
        val general = ctx.general
        val nation = ctx.nation ?: return null
        val genType = ctx.generalType

        val isWarGen = genType and GeneralType.COMMANDER.flag != 0
        val reqGold = if (isWarGen) nationPolicy.reqNationGold else (nationPolicy.reqNationGold / 2)
        val reqRice = if (isWarGen) nationPolicy.reqNationRice else (nationPolicy.reqNationRice / 2)

        var donateGold = false
        var donateRice = false

        // Check gold
        if (nation.gold < nationPolicy.reqNationGold && general.gold > reqGold * 1.5) {
            if (rng.nextDouble() < (general.gold.toDouble() / reqGold - 0.5)) {
                donateGold = true
            }
        }
        // Excess gold even if nation doesn't need it
        if (!donateGold && general.gold > reqGold * 5 && general.gold > 5000) {
            donateGold = true
        }

        // Check rice
        if (nation.rice < nationPolicy.reqNationRice && general.rice > reqRice * 1.5) {
            if (rng.nextDouble() < (general.rice.toDouble() / reqRice - 0.5)) {
                donateRice = true
            }
        }
        if (!donateRice && general.rice > reqRice * 5 && general.rice > 5000) {
            donateRice = true
        }

        // Emergency: nation rice is critically low
        if (!donateRice && nation.rice <= 500 && general.rice >= 500) {
            donateRice = true
        }

        if (!donateGold && !donateRice) return null

        // Calculate donation amounts
        if (donateGold) {
            val amount = max(general.gold - reqGold, nationPolicy.minimumResourceActionAmount)
            general.meta["donateGold"] = valueFit(amount, nationPolicy.minimumResourceActionAmount, nationPolicy.maximumResourceActionAmount)
        }
        if (donateRice) {
            val amount = max(general.rice - reqRice, nationPolicy.minimumResourceActionAmount)
            general.meta["donateRice"] = valueFit(amount, nationPolicy.minimumResourceActionAmount, nationPolicy.maximumResourceActionAmount)
        }

        return "헌납"
    }

    // ──────────────────────────────────────────────────────────
    //  do소집해제: Dismiss troops
    // ──────────────────────────────────────────────────────────

    private fun doDismiss(ctx: AIContext, rng: Random, attackable: Boolean): String? {
        if (attackable) return null
        if (ctx.diplomacyState != DiplomacyState.PEACE) return null
        if (ctx.general.crew == 0) return null
        // Per legacy: 75% chance to skip (slow disbanding)
        if (rng.nextDouble() < 0.75) return null
        return "소집해제"
    }

    // ──────────────────────────────────────────────────────────
    //  do중립: Neutral/fallback action
    // ──────────────────────────────────────────────────────────

    /**
     * Per legacy: if nation needs gold/rice, do 물자조달. Otherwise 인재탐색 or 견문.
     */
    private fun doNeutral(general: General, nation: Nation?, rng: Random): String {
        if (general.nationId == 0L) {
            // Wanderer: 인재탐색 or 견문
            return if (rng.nextDouble() < 0.2) "인재탐색" else "견문"
        }

        val candidate = mutableListOf("물자조달", "인재탐색")

        if (nation != null) {
            if (nation.gold < 2000 || nation.rice < 2000) {
                return "물자조달"
            }
        }

        return candidate[rng.nextInt(candidate.size)]
    }

    // ──────────────────────────────────────────────────────────
    //  do거병: Rise up (NPC lord founding)
    // ──────────────────────────────────────────────────────────

    private fun doRise(general: General, world: WorldState, rng: Random): String? {
        if (general.makeLimit > 0) return null
        if (general.npcState.toInt() > 2) return null

        val avgStat = (general.leadership + general.strength + general.intel).toDouble() / 3.0
        val threshold = rng.nextDouble() * 80.0  // Simplified from (defaultStatNPCMax + chiefStatMin)/2

        if (threshold >= avgStat) return null

        // Per legacy: 거병 기한 - low probability
        val yearsFromInit = world.currentYear - ((world.config["startyear"] as? Number)?.toInt() ?: world.currentYear.toInt())
        val more = valueFitD((3 - yearsFromInit).toDouble(), 1.0, 3.0)
        if (rng.nextDouble() >= 0.0075 * more) return null

        return "거병"
    }

    // ──────────────────────────────────────────────────────────
    //  doNPC사망대비: Death preparation
    // ──────────────────────────────────────────────────────────

    /**
     * Per legacy: when killTurn <= 5, donate all resources to nation.
     */
    private fun doDeathPreparation(general: General, nation: Nation?, rng: Random): String {
        if (general.nationId == 0L) {
            return if (rng.nextDouble() < 0.5) "인재탐색" else "견문"
        }

        if (general.gold + general.rice == 0) return "물자조달"

        // Donate whichever resource is higher
        return if (general.gold >= general.rice) {
            general.meta["donateGold"] = general.gold
            "헌납"
        } else {
            general.meta["donateRice"] = general.rice
            "헌납"
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Utility: weighted random choice
    // ──────────────────────────────────────────────────────────

    /**
     * Choose an item from a list with weights computed by [weightFn].
     */
    private fun <T> choiceByWeight(rng: Random, items: List<T>, weightFn: (T) -> Double): T? {
        if (items.isEmpty()) return null
        if (items.size == 1) return items[0]
        val weights = items.map { weightFn(it) }
        val totalWeight = weights.sum()
        if (totalWeight <= 0) return items[rng.nextInt(items.size)]
        var r = rng.nextDouble() * totalWeight
        for (i in items.indices) {
            r -= weights[i]
            if (r <= 0) return items[i]
        }
        return items.last()
    }

    /**
     * Choose from list of Pair(item, weight).
     */
    private fun <T> choiceByWeightPair(rng: Random, items: List<Pair<T, Double>>): T? {
        if (items.isEmpty()) return null
        val totalWeight = items.sumOf { it.second }
        if (totalWeight <= 0) return items[rng.nextInt(items.size)].first
        var r = rng.nextDouble() * totalWeight
        for ((item, w) in items) {
            r -= w
            if (r <= 0) return item
        }
        return items.last().first
    }

    /**
     * Choose from list of Pair(item, weight) where items are raw values.
     */
    private fun <T> choiceByWeightPairRaw(rng: Random, items: List<Pair<T, Double>>): T? {
        return choiceByWeightPair(rng, items)
    }

    // ──────────────────────────────────────────────────────────
    //  Utility: value clamping
    // ──────────────────────────────────────────────────────────

    private fun valueFit(value: Int, min: Int, max: Int): Int {
        return value.coerceIn(min, max)
    }

    private fun valueFitD(value: Double, min: Double, max: Double = Double.MAX_VALUE): Double {
        return value.coerceIn(min, max)
    }
}
