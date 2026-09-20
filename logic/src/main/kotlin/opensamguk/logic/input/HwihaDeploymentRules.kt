package opensamguk.logic.input

import opensamguk.logic.world.StrategicNodeRef

/** Caller must supply a complete, validated position and deployment snapshot. */
data class DeploymentPerson(val id: Int, val nationId: Int, val isUnownedNpc: Boolean,
    val node: StrategicNodeRef?, val inBattle: Boolean)
data class DeploymentUnit(val id: Int, val ownerId: Int, val troops: Int, val commanderRetainerId: Int?)
data class DeploymentRetainer(val id: Int, val ownerId: Int, val generalId: Int?, val isLieutenant: Boolean)
data class DeploymentProjection(val profile: RuleProfile, val people: List<DeploymentPerson>,
    val units: List<DeploymentUnit>, val retainers: List<DeploymentRetainer>, val deployed: List<HwihaDeployedCorps>)
data class DeploymentRequest(val ownerId: Int, val commanderRetainerId: Int?, val bugokIds: List<Int>)
enum class DeploymentFailure {
    WRONG_RULE_PROFILE, INVALID_INPUT, OWNER_UNAVAILABLE, COMMANDER_UNAVAILABLE, DIFFERENT_NATION,
    POSITION_UNAVAILABLE, MUST_ASSEMBLE, BATTLE_PENDING, UNIT_UNAVAILABLE, COMMANDER_CHANGED,
    ALREADY_DEPLOYED, STATE_UNAVAILABLE, INVALID_DESTINATION, NO_ROUTE,
}
sealed interface DeploymentAssessment {
    data class Eligible(val owner: DeploymentPerson, val commander: DeploymentPerson,
        val units: List<DeploymentUnit>) : DeploymentAssessment
    data class Rejected(val reason: DeploymentFailure) : DeploymentAssessment
}

/** No troop count, supplies, or location is copied into deployment metadata. */
object HwihaDeploymentRules {
    fun assess(request: DeploymentRequest, state: DeploymentProjection): DeploymentAssessment {
        val base = relationship(request, state)
        if (base !is DeploymentAssessment.Eligible) return base
        if (state.deployed.any { it.commanderGeneralId == base.commander.id || it.bugokIds.any(request.bugokIds::contains) })
            return reject(DeploymentFailure.ALREADY_DEPLOYED)
        if (base.owner.inBattle || base.commander.inBattle) return reject(DeploymentFailure.BATTLE_PENDING)
        if (base.owner.node !is StrategicNodeRef.LandProvince) return reject(DeploymentFailure.POSITION_UNAVAILABLE)
        if (base.owner.node != base.commander.node) return reject(DeploymentFailure.MUST_ASSEMBLE)
        return base
    }

    /** Recheck current allegiance, cards, and troops; malformed/stale corps never become ordinary residents. */
    fun assessActive(corps: HwihaDeployedCorps, state: DeploymentProjection): DeploymentAssessment {
        val base = relationship(DeploymentRequest(corps.ownerGeneralId, corps.commanderRetainerId, corps.bugokIds), state)
        if (base !is DeploymentAssessment.Eligible) return base
        if (base.commander.id != corps.commanderGeneralId) return reject(DeploymentFailure.COMMANDER_CHANGED)
        if (base.owner.nationId != corps.nationId) return reject(DeploymentFailure.DIFFERENT_NATION)
        if (state.deployed.count { it == corps } != 1) return reject(DeploymentFailure.STATE_UNAVAILABLE)
        if (state.deployed.any { it != corps && (it.commanderGeneralId == corps.commanderGeneralId ||
                it.bugokIds.any(corps.bugokIds::contains)) }) return reject(DeploymentFailure.STATE_UNAVAILABLE)
        return base
    }

    private fun relationship(request: DeploymentRequest, state: DeploymentProjection): DeploymentAssessment {
        if (state.profile != RuleProfile.HWIHA) return reject(DeploymentFailure.WRONG_RULE_PROFILE)
        if (request.ownerId <= 0 || request.commanderRetainerId?.let { it <= 0 } == true ||
            request.bugokIds.isEmpty() || request.bugokIds.any { it <= 0 } ||
            request.bugokIds.distinct().size != request.bugokIds.size) return reject(DeploymentFailure.INVALID_INPUT)
        if (state.people.map { it.id }.distinct().size != state.people.size ||
            state.units.map { it.id }.distinct().size != state.units.size ||
            state.retainers.map { it.id }.distinct().size != state.retainers.size)
            return reject(DeploymentFailure.STATE_UNAVAILABLE)
        val owner = state.people.singleOrNull { it.id == request.ownerId }
            ?: return reject(DeploymentFailure.OWNER_UNAVAILABLE)
        val commander = if (request.commanderRetainerId == null) owner else {
            val card = state.retainers.singleOrNull { it.id == request.commanderRetainerId }
                ?.takeIf { it.ownerId == owner.id && it.isLieutenant && it.generalId != owner.id }
                ?: return reject(DeploymentFailure.COMMANDER_UNAVAILABLE)
            if (state.retainers.count { it.generalId == card.generalId } != 1)
                return reject(DeploymentFailure.COMMANDER_UNAVAILABLE)
            state.people.singleOrNull { it.id == card.generalId }?.takeIf { it.isUnownedNpc }
                ?: return reject(DeploymentFailure.COMMANDER_UNAVAILABLE)
        }
        if (owner.nationId < 0 || owner.nationId != commander.nationId) return reject(DeploymentFailure.DIFFERENT_NATION)
        if (commander.node !is StrategicNodeRef.LandProvince)
            return reject(DeploymentFailure.POSITION_UNAVAILABLE)
        val units = request.bugokIds.sorted().map { id ->
            val unit = state.units.singleOrNull { it.id == id }?.takeIf { it.ownerId == owner.id && it.troops > 0 }
                ?: return reject(DeploymentFailure.UNIT_UNAVAILABLE)
            if (unit.commanderRetainerId != request.commanderRetainerId) return reject(DeploymentFailure.COMMANDER_CHANGED)
            unit
        }
        return DeploymentAssessment.Eligible(owner, commander, units)
    }
    private fun reject(reason: DeploymentFailure) = DeploymentAssessment.Rejected(reason)
}
