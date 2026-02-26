package com.opensam.engine.ai

import com.opensam.entity.Nation
import com.opensam.model.CrewType
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * NPC general policy - mirrors legacy AutorunGeneralPolicy.
 * Controls what actions an NPC general can take and in what priority.
 */
class NpcGeneralPolicy(
    val priority: List<String> = DEFAULT_GENERAL_PRIORITY,
    val enabledActions: Set<String> = DEFAULT_ENABLED_ACTIONS,
    val limitActions: Set<String> = emptySet(),
    val minWarCrew: Int = 500,
    val properWarTrainAtmos: Int = 80,
) {
    fun canDo(action: String): Boolean = action in enabledActions
    fun isLimitAction(action: String): Boolean = action in limitActions

    companion object {
        val DEFAULT_GENERAL_PRIORITY = listOf(
            "긴급내정", "전쟁내정", "징병", "전투준비", "출병",
            "전방워프", "후방워프", "내정워프", "귀환",
            "일반내정", "금쌀구매", "NPC헌납", "소집해제", "중립"
        )
        val DEFAULT_ENABLED_ACTIONS = setOf(
            "일반내정", "긴급내정", "전쟁내정", "징병", "전투준비", "출병",
            "금쌀구매", "NPC헌납", "전방워프", "후방워프", "내정워프",
            "귀환", "소집해제", "중립", "국가선택", "모병"
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NpcGeneralPolicy) return false
        return priority == other.priority && enabledActions == other.enabledActions
            && limitActions == other.limitActions && minWarCrew == other.minWarCrew
            && properWarTrainAtmos == other.properWarTrainAtmos
    }

    override fun hashCode(): Int {
        var result = priority.hashCode()
        result = 31 * result + enabledActions.hashCode()
        result = 31 * result + limitActions.hashCode()
        result = 31 * result + minWarCrew
        result = 31 * result + properWarTrainAtmos
        return result
    }
}

/**
 * NPC nation policy - mirrors legacy AutorunNationPolicy.
 * Controls what nation-level actions the AI ruler can take.
 */
class NpcNationPolicy(
    val priority: List<String> = DEFAULT_NATION_PRIORITY,
    val enabledActions: Set<String> = DEFAULT_ENABLED_ACTIONS,
    val minNPCWarLeadership: Int = 40,
    val minNPCRecruitCityPopulation: Int = 50000,
    val safeRecruitCityPopulationRatio: Double = 0.5,
    val reqNationGold: Int = 10000,
    val reqNationRice: Int = 12000,
    val reqHumanWarUrgentGold: Int = 0,
    val reqHumanWarUrgentRice: Int = 0,
    val reqHumanWarRecommandGold: Int = 0,
    val reqHumanWarRecommandRice: Int = 0,
    val reqHumanDevelGold: Int = 10000,
    val reqHumanDevelRice: Int = 10000,
    val reqNPCWarGold: Int = 0,
    val reqNPCWarRice: Int = 0,
    val reqNPCDevelGold: Int = 0,
    val reqNPCDevelRice: Int = 500,
    val minimumResourceActionAmount: Int = 1000,
    val maximumResourceActionAmount: Int = 10000,
    val minWarCrew: Int = 1500,
    val properWarTrainAtmos: Int = 90,
    val cureThreshold: Int = 10,
    val combatForce: Map<Int, Pair<Int, Int>> = emptyMap(),
    val supportForce: List<Int> = emptyList(),
    val developForce: List<Int> = emptyList(),
) {
    fun canDo(action: String): Boolean = action in enabledActions

    fun calcPolicyValue(fieldName: String, nation: Nation): Int {
        return when (fieldName) {
            "reqNPCDevelGold" -> if (reqNPCDevelGold == 0) defaultDevelCost * 30 else reqNPCDevelGold
            "reqNPCWarGold" -> if (reqNPCWarGold == 0) roundToHundreds(calcDefaultNpcWarGold(nation)) else reqNPCWarGold
            "reqNPCWarRice" -> if (reqNPCWarRice == 0) roundToHundreds(calcDefaultNpcWarRice(nation)) else reqNPCWarRice
            "reqHumanWarUrgentGold" -> if (reqHumanWarUrgentGold == 0) roundToHundreds(calcDefaultHumanWarUrgentGold(nation)) else reqHumanWarUrgentGold
            "reqHumanWarUrgentRice" -> if (reqHumanWarUrgentRice == 0) roundToHundreds(calcDefaultHumanWarUrgentRice(nation)) else reqHumanWarUrgentRice
            "reqHumanWarRecommandGold" -> {
                if (reqHumanWarRecommandGold == 0) {
                    roundToHundreds(calcPolicyValue("reqHumanWarUrgentGold", nation) * 2)
                } else {
                    reqHumanWarRecommandGold
                }
            }
            "reqHumanWarRecommandRice" -> {
                if (reqHumanWarRecommandRice == 0) {
                    roundToHundreds(calcPolicyValue("reqHumanWarUrgentRice", nation) * 2)
                } else {
                    reqHumanWarRecommandRice
                }
            }
            else -> getRawIntValue(fieldName)
        }
    }

    private fun getRawIntValue(fieldName: String): Int {
        return when (fieldName) {
            "reqNationGold" -> reqNationGold
            "reqNationRice" -> reqNationRice
            "reqHumanWarUrgentGold" -> reqHumanWarUrgentGold
            "reqHumanWarUrgentRice" -> reqHumanWarUrgentRice
            "reqHumanWarRecommandGold" -> reqHumanWarRecommandGold
            "reqHumanWarRecommandRice" -> reqHumanWarRecommandRice
            "reqHumanDevelGold" -> reqHumanDevelGold
            "reqHumanDevelRice" -> reqHumanDevelRice
            "reqNPCWarGold" -> reqNPCWarGold
            "reqNPCWarRice" -> reqNPCWarRice
            "reqNPCDevelGold" -> reqNPCDevelGold
            "reqNPCDevelRice" -> reqNPCDevelRice
            "minimumResourceActionAmount" -> minimumResourceActionAmount
            "maximumResourceActionAmount" -> maximumResourceActionAmount
            "minNPCWarLeadership" -> minNPCWarLeadership
            "minWarCrew" -> minWarCrew
            "minNPCRecruitCityPopulation" -> minNPCRecruitCityPopulation
            "safeRecruitCityPopulationRatio" -> (safeRecruitCityPopulationRatio * 100).toInt()
            "properWarTrainAtmos" -> properWarTrainAtmos
            "cureThreshold" -> cureThreshold
            else -> 0
        }
    }

    private fun calcDefaultHumanWarUrgentGold(nation: Nation): Int {
        return calcCrewCostWithTech(nation, defaultStatMax * 100) * 6
    }

    private fun calcDefaultHumanWarUrgentRice(nation: Nation): Int {
        return calcCrewRiceWithTech(nation, defaultStatMax * 100) * 6
    }

    private fun calcDefaultNpcWarGold(nation: Nation): Int {
        return calcCrewCostWithTech(nation, defaultStatNpcMax * 100) * 4
    }

    private fun calcDefaultNpcWarRice(nation: Nation): Int {
        return calcCrewRiceWithTech(nation, defaultStatNpcMax * 100) * 4
    }

    private fun calcCrewCostWithTech(nation: Nation, maxCrew: Int): Int {
        val techCost = 1.0 + floor(nation.tech.toDouble() / 1000.0) * 0.15
        return (defaultCrewType.cost * techCost * maxCrew / 100.0).roundToInt()
    }

    private fun calcCrewRiceWithTech(nation: Nation, maxCrew: Int): Int {
        val techCost = 1.0 + floor(nation.tech.toDouble() / 1000.0) * 0.15
        return (defaultCrewType.riceCost * techCost * maxCrew / 100.0).roundToInt()
    }

    private fun roundToHundreds(value: Int): Int {
        return ((value + 50) / 100) * 100
    }

    companion object {
        val DEFAULT_NATION_PRIORITY = listOf(
            "부대전방발령", "부대후방발령", "부대구출발령",
            "부대유저장후방발령",
            "NPC전방발령", "NPC후방발령", "NPC내정발령",
            "NPC구출발령",
            "유저장전방발령", "유저장후방발령",
            "유저장구출발령", "유저장내정발령",
            "NPC긴급포상", "유저장긴급포상",
            "NPC포상", "유저장포상", "NPC몰수",
            "불가침제의", "선전포고",
            "천도", "전시전략"
        )
        val DEFAULT_ENABLED_ACTIONS = setOf(
            "부대전방발령", "부대후방발령", "부대구출발령",
            "부대유저장후방발령",
            "NPC전방발령", "NPC후방발령", "NPC내정발령",
            "NPC구출발령",
            "유저장전방발령", "유저장후방발령",
            "유저장구출발령", "유저장내정발령",
            "NPC긴급포상", "유저장긴급포상",
            "NPC포상", "유저장포상", "NPC몰수",
            "불가침제의", "선전포고", "천도",
            "전시전략"
        )
        val AVAILABLE_INSTANT_TURN = setOf(
            "NPC긴급포상", "유저장긴급포상",
            "NPC전방발령", "NPC후방발령",
            "유저장전방발령", "유저장후방발령",
            "부대전방발령", "부대후방발령",
            "부대유저장후방발령",
            "유저장구출발령", "NPC구출발령", "부대구출발령"
        )
        private const val defaultStatMax = 70
        private const val defaultStatNpcMax = 60
        private const val defaultDevelCost = 100
        private val defaultCrewType = CrewType.FOOTMAN
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NpcNationPolicy) return false
        return priority == other.priority && enabledActions == other.enabledActions
            && minNPCWarLeadership == other.minNPCWarLeadership
            && minNPCRecruitCityPopulation == other.minNPCRecruitCityPopulation
            && safeRecruitCityPopulationRatio == other.safeRecruitCityPopulationRatio
            && reqNationGold == other.reqNationGold
            && reqNationRice == other.reqNationRice
            && reqHumanWarUrgentGold == other.reqHumanWarUrgentGold
            && reqHumanWarUrgentRice == other.reqHumanWarUrgentRice
            && reqHumanWarRecommandGold == other.reqHumanWarRecommandGold
            && reqHumanWarRecommandRice == other.reqHumanWarRecommandRice
            && reqHumanDevelGold == other.reqHumanDevelGold
            && reqHumanDevelRice == other.reqHumanDevelRice
            && reqNPCWarGold == other.reqNPCWarGold
            && reqNPCWarRice == other.reqNPCWarRice
            && reqNPCDevelGold == other.reqNPCDevelGold
            && reqNPCDevelRice == other.reqNPCDevelRice
            && minimumResourceActionAmount == other.minimumResourceActionAmount
            && maximumResourceActionAmount == other.maximumResourceActionAmount
            && minWarCrew == other.minWarCrew
            && properWarTrainAtmos == other.properWarTrainAtmos
            && cureThreshold == other.cureThreshold
            && combatForce == other.combatForce
            && supportForce == other.supportForce
            && developForce == other.developForce
    }

    override fun hashCode(): Int {
        var result = priority.hashCode()
        result = 31 * result + enabledActions.hashCode()
        result = 31 * result + minNPCWarLeadership
        result = 31 * result + minNPCRecruitCityPopulation
        result = 31 * result + safeRecruitCityPopulationRatio.hashCode()
        result = 31 * result + reqNationGold
        result = 31 * result + reqNationRice
        result = 31 * result + reqHumanWarUrgentGold
        result = 31 * result + reqHumanWarUrgentRice
        result = 31 * result + reqHumanWarRecommandGold
        result = 31 * result + reqHumanWarRecommandRice
        result = 31 * result + reqHumanDevelGold
        result = 31 * result + reqHumanDevelRice
        result = 31 * result + reqNPCWarGold
        result = 31 * result + reqNPCWarRice
        result = 31 * result + reqNPCDevelGold
        result = 31 * result + reqNPCDevelRice
        result = 31 * result + minimumResourceActionAmount
        result = 31 * result + maximumResourceActionAmount
        result = 31 * result + minWarCrew
        result = 31 * result + properWarTrainAtmos
        result = 31 * result + cureThreshold
        result = 31 * result + combatForce.hashCode()
        result = 31 * result + supportForce.hashCode()
        result = 31 * result + developForce.hashCode()
        return result
    }

}

/**
 * Build policies from nation meta. Legacy stores these in KVStorage; we use nation.meta.
 */
object NpcPolicyBuilder {

    @Suppress("UNCHECKED_CAST")
    fun buildGeneralPolicy(nationMeta: Map<String, Any>): NpcGeneralPolicy {
        val raw = (nationMeta["npcGeneralPolicy"] as? Map<String, Any>)
            ?: (nationMeta["npcPriority"] as? Map<String, Any>)
            ?: return NpcGeneralPolicy()
        return NpcGeneralPolicy(
            priority = (raw["priority"] as? List<String>)
                ?: NpcGeneralPolicy.DEFAULT_GENERAL_PRIORITY,
            minWarCrew = (raw["minWarCrew"] as? Number)?.toInt() ?: 500,
            properWarTrainAtmos = (raw["properWarTrainAtmos"] as? Number)?.toInt() ?: 80,
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun buildNationPolicy(nationMeta: Map<String, Any>): NpcNationPolicy {
        val raw = (nationMeta["npcNationPolicy"] as? Map<String, Any>)
            ?: (nationMeta["npcPolicy"] as? Map<String, Any>)
            ?: return NpcNationPolicy()

        val combatForceRaw =
            (raw["combatForce"] as? Map<*, *>)
                ?: (raw["CombatForce"] as? Map<*, *>)
                ?: emptyMap<Any, Any>()

        val supportForceRaw =
            (raw["supportForce"] as? List<*>)
                ?: (raw["SupportForce"] as? List<*>)
                ?: emptyList<Any>()

        val developForceRaw =
            (raw["developForce"] as? List<*>)
                ?: (raw["DevelopForce"] as? List<*>)
                ?: emptyList<Any>()

        return NpcNationPolicy(
            priority = (raw["priority"] as? List<String>)
                ?: NpcNationPolicy.DEFAULT_NATION_PRIORITY,
            minNPCWarLeadership = (raw["minNPCWarLeadership"] as? Number)?.toInt() ?: 40,
            minNPCRecruitCityPopulation = (raw["minNPCRecruitCityPopulation"] as? Number)?.toInt() ?: 50000,
            safeRecruitCityPopulationRatio = (raw["safeRecruitCityPopulationRatio"] as? Number)?.toDouble() ?: 0.5,
            reqNationGold = (raw["reqNationGold"] as? Number)?.toInt() ?: 10000,
            reqNationRice = (raw["reqNationRice"] as? Number)?.toInt() ?: 12000,
            reqHumanWarUrgentGold = (raw["reqHumanWarUrgentGold"] as? Number)?.toInt() ?: 0,
            reqHumanWarUrgentRice = (raw["reqHumanWarUrgentRice"] as? Number)?.toInt() ?: 0,
            reqHumanWarRecommandGold = (raw["reqHumanWarRecommandGold"] as? Number)?.toInt() ?: 0,
            reqHumanWarRecommandRice = (raw["reqHumanWarRecommandRice"] as? Number)?.toInt() ?: 0,
            reqHumanDevelGold = (raw["reqHumanDevelGold"] as? Number)?.toInt() ?: 10000,
            reqHumanDevelRice = (raw["reqHumanDevelRice"] as? Number)?.toInt() ?: 10000,
            reqNPCWarGold = (raw["reqNPCWarGold"] as? Number)?.toInt() ?: 0,
            reqNPCWarRice = (raw["reqNPCWarRice"] as? Number)?.toInt() ?: 0,
            reqNPCDevelGold = (raw["reqNPCDevelGold"] as? Number)?.toInt() ?: 0,
            reqNPCDevelRice = (raw["reqNPCDevelRice"] as? Number)?.toInt() ?: 500,
            minimumResourceActionAmount = (raw["minimumResourceActionAmount"] as? Number)?.toInt() ?: 1000,
            maximumResourceActionAmount = (raw["maximumResourceActionAmount"] as? Number)?.toInt() ?: 10000,
            minWarCrew = (raw["minWarCrew"] as? Number)?.toInt() ?: 1500,
            properWarTrainAtmos = (raw["properWarTrainAtmos"] as? Number)?.toInt() ?: 90,
            cureThreshold = (raw["cureThreshold"] as? Number)?.toInt() ?: 10,
            combatForce = combatForceRaw.entries
                .mapNotNull { entry ->
                    val key = (entry.key as? Number)?.toInt() ?: return@mapNotNull null
                    val value = entry.value
                    val pair = when (value) {
                        is Pair<*, *> -> {
                            val from = (value.first as? Number)?.toInt() ?: return@mapNotNull null
                            val to = (value.second as? Number)?.toInt() ?: return@mapNotNull null
                            from to to
                        }
                        is List<*> -> {
                            if (value.size < 2) return@mapNotNull null
                            val from = (value[0] as? Number)?.toInt() ?: return@mapNotNull null
                            val to = (value[1] as? Number)?.toInt() ?: return@mapNotNull null
                            from to to
                        }
                        else -> return@mapNotNull null
                    }
                    key to pair
                }
                .toMap(),
            supportForce = supportForceRaw.mapNotNull { (it as? Number)?.toInt() },
            developForce = developForceRaw.mapNotNull { (it as? Number)?.toInt() },
        )
    }
}
