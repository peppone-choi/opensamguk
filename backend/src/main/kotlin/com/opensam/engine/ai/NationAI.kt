package com.opensam.engine.ai

import com.opensam.entity.*
import com.opensam.repository.*
import org.springframework.stereotype.Service
import kotlin.random.Random

@Service
class NationAI(
    private val cityRepository: CityRepository,
    private val generalRepository: GeneralRepository,
    private val nationRepository: NationRepository,
    private val diplomacyRepository: DiplomacyRepository,
) {
    fun decideNationAction(nation: Nation, world: WorldState, rng: Random): String {
        val worldId = world.id.toLong()
        val nationCities = cityRepository.findByNationId(nation.id)
        val nationGenerals = generalRepository.findByNationId(nation.id)
        val diplomacies = diplomacyRepository.findByWorldIdAndIsDeadFalse(worldId)
        val policy = NpcPolicyBuilder.buildNationPolicy(nation.meta)

        val atWar = diplomacies.any {
            it.stateCode == "선전포고" &&
                (it.srcNationId == nation.id || it.destNationId == nation.id)
        } || nation.warState > 0

        // Low funds: use policy thresholds
        if (nation.gold < policy.reqNationGold || nation.rice < policy.reqNationRice) {
            return "Nation휴식"
        }

        // At war: strategic commands
        if (atWar) {
            if (nation.strategicCmdLimit > 0) {
                val warActions = listOf("급습", "의병모집", "필사즉생")
                return warActions[rng.nextInt(warActions.size)]
            }
            return "Nation휴식"
        }

        val candidates = linkedSetOf<String>()

        // Assign unassigned generals
        val unassigned = nationGenerals.filter {
            it.officerLevel.toInt() == 0 && it.npcState.toInt() != 5
        }
        if (unassigned.isNotEmpty()) candidates.add("발령")

        // Expand cities
        if (nation.gold > 5000 && nationCities.any { it.level < 5 }) candidates.add("증축")

        // Reward generals with low dedication
        if (nation.gold > 3000 && nationGenerals.any { it.dedication < 80 }) candidates.add("포상")

        // Consider non-aggression pact (불가침제의)
        if (shouldConsiderNAP(nation, diplomacies, rng)) {
            candidates.add("불가침제의")
        }

        // Consider war declaration
        val allNations = nationRepository.findByWorldId(worldId)
        if (shouldConsiderWar(nation, allNations, diplomacies, rng)) {
            candidates.add("선전포고")
        }

        // Consider capital relocation (천도)
        if (shouldConsiderCapitalMove(nation, nationCities)) {
            candidates.add("천도")
        }

        if (candidates.isEmpty()) {
            return "Nation휴식"
        }

        for (priority in policy.priority) {
            if (!policy.canDo(priority)) continue
            val mapped = mapNationPriorityToAction(priority) ?: continue
            if (mapped in candidates) return mapped
        }

        return candidates.first()
    }

    fun shouldDeclareWar(nation: Nation, targetNation: Nation, world: WorldState): Boolean {
        val nationCities = cityRepository.findByNationId(nation.id)
        val targetCities = cityRepository.findByNationId(targetNation.id)
        val nationGenerals = generalRepository.findByNationId(nation.id)
        val targetGenerals = generalRepository.findByNationId(targetNation.id)

        // Power comparison
        if (nation.power < targetNation.power) return false

        // Need sufficient cities and generals
        if (nationCities.size < 2) return false
        if (nationGenerals.size < targetGenerals.size) return false

        // Need sufficient resources
        if (nation.gold < 5000 || nation.rice < 5000) return false

        return true
    }

    /**
     * Consider non-aggression pact when not at war and have neighbors without existing NAP.
     */
    private fun shouldConsiderNAP(
        nation: Nation,
        diplomacies: List<Diplomacy>,
        rng: Random,
    ): Boolean {
        // Don't propose NAP if low on resources
        if (nation.gold < 5000) return false

        // Find nations that already have diplomacy with us
        val existingDiploNationIds = diplomacies
            .filter { it.srcNationId == nation.id || it.destNationId == nation.id }
            .flatMap { listOf(it.srcNationId, it.destNationId) }
            .toSet()

        // We need neighboring nations without existing diplomacy
        val neighborCities = cityRepository.findByNationId(nation.id).filter { it.frontState > 0 }
        if (neighborCities.isEmpty()) return false

        // Low probability of NAP proposal
        return rng.nextInt(100) < 15
    }

    /**
     * Consider capital relocation when current capital is not the best city.
     * Per legacy: move capital based on population, development, and connectivity.
     */
    private fun shouldConsiderCapitalMove(nation: Nation, nationCities: List<City>): Boolean {
        val capitalId = nation.capitalCityId ?: return false
        if (nationCities.size < 2) return false
        val capital = nationCities.find { it.id == capitalId } ?: return false

        // Check if another city has significantly better population
        val bestCity = nationCities.maxByOrNull { it.pop } ?: return false
        return bestCity.id != capital.id && bestCity.pop > capital.pop * 1.5
    }

    private fun shouldConsiderWar(
        nation: Nation,
        allNations: List<Nation>,
        diplomacies: List<Diplomacy>,
        rng: Random,
    ): Boolean {
        if (nation.gold < 10000 || nation.rice < 10000) return false

        // Find nations not already in diplomacy
        val existingDiploNationIds = diplomacies
            .filter { it.srcNationId == nation.id || it.destNationId == nation.id }
            .flatMap { listOf(it.srcNationId, it.destNationId) }
            .toSet()

        val targets = allNations.filter {
            it.id != nation.id && it.id !in existingDiploNationIds && it.power < nation.power
        }

        // Low probability of war declaration
        return targets.isNotEmpty() && rng.nextInt(100) < 10
    }

    private fun mapNationPriorityToAction(priority: String): String? {
        return when (priority) {
            "부대전방발령", "부대후방발령", "부대구출발령",
            "NPC전방발령", "NPC후방발령", "NPC내정발령",
            "유저장전방발령", "유저장후방발령" -> "발령"
            "NPC포상", "유저장포상" -> "포상"
            "NPC몰수" -> "몰수"
            "불가침제의" -> "불가침제의"
            "선전포고" -> "선전포고"
            "천도" -> "천도"
            else -> null
        }
    }
}
