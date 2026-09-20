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

    private fun assessFor(nationId: Int, actorId: Int?, state: DeploymentProjection,
        wars: Set<Pair<Int, Int>>): MilitaryPresenceAssessment {
        if (state.profile != RuleProfile.HWIHA ||
            state.people.map { it.id }.distinct().size != state.people.size ||
            state.units.map { it.id }.distinct().size != state.units.size ||
            state.retainers.map { it.id }.distinct().size != state.retainers.size)
            return MilitaryPresenceAssessment.Unavailable
        // Neutral allegiance follows explicit personal ownership, including non-deployed retainers.
        // Never infer kinship from co-location; malformed relevant chains withhold authority.
        fun root(personId: Int): Int? {
            var current = personId
            val seen = mutableSetOf<Int>()
            while (seen.add(current)) {
                val person = state.people.singleOrNull { it.id == current } ?: return null
                val links = state.retainers.filter { it.generalId == current }
                if (links.isEmpty()) return current
                val link = links.singleOrNull() ?: return null
                val master = state.people.singleOrNull { it.id == link.ownerId } ?: return null
                if (master.nationId != person.nationId) return null
                current = master.id
            }
            return null
        }
        val actorRoot = if (nationId == 0 && actorId != null) root(actorId)
            ?: return MilitaryPresenceAssessment.Unavailable else null
        val hostile = mutableListOf<HwihaDeployedCorps>()
        val blocked = sortedSetOf<String>()
        for (corps in state.deployed.sortedWith(compareBy({ it.ownerGeneralId }, { it.commanderGeneralId }, { it.orderId }))) {
            val active = HwihaDeploymentRules.assessActive(corps, state) as? DeploymentAssessment.Eligible
                ?: return MilitaryPresenceAssessment.Unavailable
            val corpsRoot = if (corps.nationId == 0) root(corps.ownerGeneralId)
                ?: return MilitaryPresenceAssessment.Unavailable else null
            if (actorRoot != null && actorRoot == corpsRoot) continue
            if (corps.ownerGeneralId == actorId || corps.commanderGeneralId == actorId || (nationId > 0 && corps.nationId == nationId)) continue
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
