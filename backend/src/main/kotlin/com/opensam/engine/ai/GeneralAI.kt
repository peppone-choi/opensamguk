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
import kotlin.random.Random

@Service
class GeneralAI(
    private val generalRepository: GeneralRepository,
    private val cityRepository: CityRepository,
    private val nationRepository: NationRepository,
    private val diplomacyRepository: DiplomacyRepository,
) {
    private val logger = LoggerFactory.getLogger(GeneralAI::class.java)

    fun decideAndExecute(general: General, world: WorldState): String {
        val rng = DeterministicRng.create(
            "${world.id}", "GeneralAI", world.currentYear, world.currentMonth, general.id
        )

        val worldId = world.id.toLong()
        val city = cityRepository.findById(general.cityId).orElse(null) ?: return "휴식"
        val nation = if (general.nationId != 0L) {
            nationRepository.findById(general.nationId).orElse(null)
        } else {
            null
        }

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
        val nationGenerals = allGenerals.filter { it.nationId == general.nationId }

        val diplomacyState = calcDiplomacyState(nation, diplomacies)
        val generalType = classifyGeneral(general)

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
        val nationPolicy = if (nation != null) {
            NpcPolicyBuilder.buildNationPolicy(nation.meta)
        } else {
            NpcNationPolicy()
        }

        val action = try {
            when {
                general.officerLevel >= 12 && nation != null -> decideChiefAction(ctx, rng, nationPolicy, generalPolicy)
                diplomacyState == DiplomacyState.AT_WAR || diplomacyState == DiplomacyState.RECRUITING -> decideWarAction(ctx, rng, generalPolicy)
                diplomacyState == DiplomacyState.IMMINENT -> decideWarAction(ctx, rng, generalPolicy)
                else -> decidePeaceAction(ctx, rng, generalPolicy)
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

    private fun calcDiplomacyState(nation: Nation?, diplomacies: List<Diplomacy>): DiplomacyState {
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

    private fun classifyGeneral(general: General): Int {
        var flags = 0
        val l = general.leadership.toInt()
        val s = general.strength.toInt()
        val i = general.intel.toInt()

        if (s >= l && s >= i) flags = flags or GeneralType.WARRIOR.flag
        if (i >= l && i >= s) flags = flags or GeneralType.STRATEGIST.flag
        if (l >= 70) flags = flags or GeneralType.COMMANDER.flag

        return flags
    }

    private fun decideChiefAction(
        ctx: AIContext,
        rng: Random,
        nationPolicy: NpcNationPolicy,
        generalPolicy: NpcGeneralPolicy,
    ): String {
        val nation = ctx.nation ?: return "휴식"
        val candidates = linkedSetOf<String>()

        if (ctx.diplomacyState == DiplomacyState.AT_WAR) {
            if (nation.strategicCmdLimit > 0) {
                val strategicActions = listOf("급습", "필사즉생", "의병모집")
                candidates.add(strategicActions[rng.nextInt(strategicActions.size)])
            }
            candidates.add(decideWarAction(ctx, rng, generalPolicy))
        }

        val nationCities = ctx.allCities.filter { it.nationId == nation.id }
        val nationGenerals = ctx.nationGenerals

        val unassigned = nationGenerals.filter { it.officerLevel.toInt() == 0 && it.npcState.toInt() != 5 }
        if (unassigned.isNotEmpty()) candidates.add("발령")

        if (nation.gold > 5000 && nationCities.any { it.level < 5 }) candidates.add("증축")

        val otherNations = ctx.allNations.filter { it.id != nation.id }
        if (otherNations.isNotEmpty() && nation.gold > 10000 && nation.rice > 10000) {
            val weakTarget = otherNations.find { it.power < nation.power }
            if (weakTarget != null) candidates.add("선전포고")
        }

        if (nation.gold > 3000 && nationGenerals.any { it.dedication < 80 }) candidates.add("포상")
        if (candidates.isEmpty()) return "Nation휴식"

        for (priority in nationPolicy.priority) {
            if (!nationPolicy.canDo(priority)) continue
            val mapped = mapNationPriorityToAction(priority, rng) ?: continue
            if (mapped in candidates) return mapped
        }

        return candidates.first()
    }

    private fun mapNationPriorityToAction(priority: String, rng: Random): String? {
        return when (priority) {
            "부대전방발령", "부대후방발령", "부대구출발령",
            "NPC전방발령", "NPC후방발령", "NPC내정발령",
            "유저장전방발령", "유저장후방발령" -> "발령"
            "NPC포상", "유저장포상" -> "포상"
            "NPC몰수" -> "몰수"
            "불가침제의" -> "불가침제의"
            "선전포고" -> "선전포고"
            "천도" -> "천도"
            "전시전략" -> listOf("급습", "필사즉생", "의병모집")[rng.nextInt(3)]
            else -> null
        }
    }

    private fun decideWarAction(ctx: AIContext, rng: Random, policy: NpcGeneralPolicy): String {
        val policyAction = actionByGeneralPolicy(policy, ctx, rng, true)
        if (policyAction != null) {
            return policyAction
        }

        val general = ctx.general
        val city = ctx.city

        if (general.injury > 0) return "요양"

        if (general.crew < 100 && city.nationId == general.nationId) {
            return if (general.gold > 100) "모병" else "징병"
        }

        if (general.crew > 0 && general.train < 80) return "훈련"
        if (general.crew > 0 && general.atmos < 80) return "사기진작"
        if (city.frontState > 0 && general.crew > 500) return "출병"
        if (city.frontState.toInt() == 0 && ctx.frontCities.isNotEmpty()) return "이동"
        if (general.crew < 1000 && city.nationId == general.nationId) return "모병"

        return "휴식"
    }

    private fun decidePeaceAction(ctx: AIContext, rng: Random, policy: NpcGeneralPolicy): String {
        val policyAction = actionByGeneralPolicy(policy, ctx, rng, false)
        if (policyAction != null) {
            return policyAction
        }

        val general = ctx.general
        val city = ctx.city

        if (general.injury > 0) return "요양"

        if (city.nationId == general.nationId) {
            if (city.agri < city.agriMax / 2) return "농지개간"
            if (city.comm < city.commMax / 2) return "상업투자"
            if (city.secu < city.secuMax / 2) return "치안강화"
        }

        val isWarrior = (ctx.generalType and GeneralType.WARRIOR.flag) != 0
        val isStrategist = (ctx.generalType and GeneralType.STRATEGIST.flag) != 0
        val isCommander = (ctx.generalType and GeneralType.COMMANDER.flag) != 0

        if (isWarrior) {
            if (general.crew > 0 && general.train < 100) return "훈련"
            if (general.crew < 1000 && city.nationId == general.nationId) return "모병"
            return "단련"
        }

        if (isStrategist) {
            if (city.nationId == general.nationId) {
                val devActions = listOf("기술연구", "치안강화", "농지개간")
                return devActions[rng.nextInt(devActions.size)]
            }
            return "견문"
        }

        if (isCommander) {
            if (general.crew > 0 && general.atmos < 100) return "사기진작"
            if (city.nationId == general.nationId && city.trust < city.popMax / 2) return "정착장려"
            if (general.crew < 1000 && city.nationId == general.nationId) return "모병"
        }

        if (general.crew < 1000 && city.nationId == general.nationId) return "모병"

        val fallbackActions = listOf("견문", "물자조달", "단련")
        return fallbackActions[rng.nextInt(fallbackActions.size)]
    }

    private fun actionByGeneralPolicy(
        policy: NpcGeneralPolicy,
        ctx: AIContext,
        rng: Random,
        warMode: Boolean,
    ): String? {
        for (priority in policy.priority) {
            if (!policy.canDo(priority)) continue
            val action = mapGeneralPriorityToAction(priority, ctx, rng, warMode) ?: continue
            return action
        }
        return null
    }

    private fun mapGeneralPriorityToAction(
        priority: String,
        ctx: AIContext,
        rng: Random,
        warMode: Boolean,
    ): String? {
        val general = ctx.general
        val city = ctx.city
        return when (priority) {
            "징병" -> if (general.gold > 100) "모병" else "징병"
            "전투준비" -> when {
                general.train < 80 -> "훈련"
                general.atmos < 80 -> "사기진작"
                else -> null
            }
            "출병" -> if (city.frontState > 0 && general.crew > 500) "출병" else null
            "전방워프" -> if (city.frontState.toInt() == 0 && ctx.frontCities.isNotEmpty()) "이동" else null
            "후방워프", "내정워프", "귀환" -> if (warMode) "귀환" else "이동"
            "일반내정", "긴급내정", "전쟁내정" -> when {
                city.agri < city.agriMax / 2 -> "농지개간"
                city.comm < city.commMax / 2 -> "상업투자"
                city.secu < city.secuMax / 2 -> "치안강화"
                else -> listOf("농지개간", "상업투자", "치안강화")[rng.nextInt(3)]
            }
            "금쌀구매" -> "군량매매"
            "NPC헌납" -> "헌납"
            "소집해제" -> "소집해제"
            "중립" -> "휴식"
            else -> null
        }
    }
}
