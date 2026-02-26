package com.opensam.engine.ai

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
    val minNPCRecruitCityPopulation: Int = 5000,
    val safeRecruitCityPopulationRatio: Double = 0.4,
    val reqNationGold: Int = 2000,
    val reqNationRice: Int = 2000,
    val cureThreshold: Int = 20,
    val minimumResourceActionAmount: Int = 200,
    val maximumResourceActionAmount: Int = 50000,
) {
    fun canDo(action: String): Boolean = action in enabledActions

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
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NpcNationPolicy) return false
        return priority == other.priority && enabledActions == other.enabledActions
            && minNPCWarLeadership == other.minNPCWarLeadership
            && minNPCRecruitCityPopulation == other.minNPCRecruitCityPopulation
    }

    override fun hashCode(): Int {
        var result = priority.hashCode()
        result = 31 * result + enabledActions.hashCode()
        result = 31 * result + minNPCWarLeadership
        result = 31 * result + minNPCRecruitCityPopulation
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
        return NpcNationPolicy(
            priority = (raw["priority"] as? List<String>)
                ?: NpcNationPolicy.DEFAULT_NATION_PRIORITY,
            minNPCWarLeadership = (raw["minNPCWarLeadership"] as? Number)?.toInt() ?: 40,
            minNPCRecruitCityPopulation = (raw["minNPCRecruitCityPopulation"] as? Number)?.toInt() ?: 5000,
            safeRecruitCityPopulationRatio = (raw["safeRecruitCityPopulationRatio"] as? Number)?.toDouble() ?: 0.4,
            reqNationGold = (raw["reqNationGold"] as? Number)?.toInt() ?: 2000,
            reqNationRice = (raw["reqNationRice"] as? Number)?.toInt() ?: 2000,
            cureThreshold = (raw["cureThreshold"] as? Number)?.toInt() ?: 20,
        )
    }
}
