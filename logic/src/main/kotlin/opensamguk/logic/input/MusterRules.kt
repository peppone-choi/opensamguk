package opensamguk.logic.input

import opensamguk.logic.world.*

sealed interface MusterAssessment {
    data class Eligible(val destination: StrategicNodeRef.LandProvince,
        val corps: List<DeployedCorps>) : MusterAssessment
    data class Rejected(val reason: MilitaryFailure) : MusterAssessment
}

/** Redirects the owner's active commanded bugoks to the owner's current province. */
object MusterRules {
    fun assess(actorId: Int, projection: DeploymentProjection, topology: StrategicTopologySnapshot,
        metrics: LandMarchMetricSnapshot, worldMeta: Map<String, Any?>): MusterAssessment {
        fun reject(reason: MilitaryFailure) = MusterAssessment.Rejected(reason)
        if (projection.profile != RuleProfile.HWIHA) return reject(MilitaryFailure.WRONG_RULE_PROFILE)
        val owner = projection.people.singleOrNull { it.id == actorId }
            ?: return reject(MilitaryFailure.ACTOR_NOT_FOUND)
        val destination = owner.node as? StrategicNodeRef.LandProvince
            ?: return reject(MilitaryFailure.POSITION_UNAVAILABLE)
        if (owner.inBattle) return reject(MilitaryFailure.BATTLE_PENDING)
        val owned = projection.deployed.filter { it.ownerGeneralId == actorId }.sortedBy { it.commanderGeneralId }
        if (owned.isEmpty()) return reject(MilitaryFailure.NO_COMMANDED_CORPS)
        val toMove = owned.filter { corps ->
            projection.people.singleOrNull { it.id == corps.commanderGeneralId }?.node != destination
        }
        if (toMove.isEmpty()) return reject(MilitaryFailure.NO_GATHER_TARGET)
        try {
            val edges = LandPassageState.read(worldMeta, topology)
                ?: return reject(MilitaryFailure.STATE_UNAVAILABLE)
            if (MarchReactions.presence(worldMeta).let {
                    it == MarchReactions.Presence.MISSING || it == MarchReactions.Presence.MALFORMED })
                return reject(MilitaryFailure.STATE_UNAVAILABLE)
            for (corps in toMove) {
                val active = DeploymentRules.assessActive(corps, projection)
                if (active !is DeploymentAssessment.Eligible) return reject(MilitaryFailure.STATE_UNAVAILABLE)
                val commander = active.commander
                if (commander.inBattle) return reject(MilitaryFailure.CORPS_BUSY)
                val start = commander.node as? StrategicNodeRef.LandProvince
                    ?: return reject(MilitaryFailure.POSITION_UNAVAILABLE)
                val path = StrategicPathResolver.resolveLandMarch(topology,
                    StrategicPathRequest(start, destination, 1), edges, metrics)
                if (path !is LandMarchPathResult.Resolved) return reject(MilitaryFailure.ROUTE_UNAVAILABLE)
            }
            return MusterAssessment.Eligible(destination, toMove)
        } catch (_: IllegalArgumentException) { return reject(MilitaryFailure.STATE_UNAVAILABLE) }
    }
}
