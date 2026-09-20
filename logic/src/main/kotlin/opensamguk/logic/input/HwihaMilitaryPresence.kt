package opensamguk.logic.input

import opensamguk.logic.world.StrategicNodeRef

sealed interface MilitaryPresenceAssessment {
    data class Ready(val hostileCorps: List<HwihaDeployedCorps>, val blockedProvinceIds: Set<String>) : MilitaryPresenceAssessment
    data object Unavailable : MilitaryPresenceAssessment
}

/** Only explicit, currently valid deployments establish military presence. Wars must contain active wars only. */
object HwihaMilitaryPresence {
    fun assess(actorId: Int, state: DeploymentProjection, wars: Set<Pair<Int, Int>>): MilitaryPresenceAssessment {
        val actor = state.people.singleOrNull { it.id == actorId } ?: return MilitaryPresenceAssessment.Unavailable
        if (actor.id <= 0 || actor.nationId < 0) return MilitaryPresenceAssessment.Unavailable
        return assessFor(actor.nationId, actorId, state, wars)
    }

    fun assessNation(nationId: Int, state: DeploymentProjection, wars: Set<Pair<Int, Int>>): MilitaryPresenceAssessment =
        if (nationId <= 0) MilitaryPresenceAssessment.Unavailable else assessFor(nationId, null, state, wars)

    private fun assessFor(nationId: Int, ownerId: Int?, state: DeploymentProjection,
        wars: Set<Pair<Int, Int>>): MilitaryPresenceAssessment {
        if (state.profile != RuleProfile.HWIHA ||
            state.people.map { it.id }.distinct().size != state.people.size ||
            state.units.map { it.id }.distinct().size != state.units.size ||
            state.retainers.map { it.id }.distinct().size != state.retainers.size)
            return MilitaryPresenceAssessment.Unavailable
        val hostile = mutableListOf<HwihaDeployedCorps>()
        val blocked = sortedSetOf<String>()
        for (corps in state.deployed.sortedWith(compareBy({ it.ownerGeneralId }, { it.commanderGeneralId }, { it.orderId }))) {
            val active = HwihaDeploymentRules.assessActive(corps, state) as? DeploymentAssessment.Eligible
                ?: return MilitaryPresenceAssessment.Unavailable
            if (corps.ownerGeneralId == ownerId || (nationId > 0 && corps.nationId == nationId)) continue
            // An unaffiliated actor does not infer hostility toward positive nations.
            val blocks = corps.nationId == 0 || (nationId > 0 &&
                ((nationId to corps.nationId) in wars || (corps.nationId to nationId) in wars))
            if (blocks) {
                hostile += corps
                blocked += (active.commander.node as StrategicNodeRef.LandProvince).id
            }
        }
        return MilitaryPresenceAssessment.Ready(hostile.toList(), blocked.toSet())
    }
}
