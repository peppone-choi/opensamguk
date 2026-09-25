package opensamguk.logic.input

import opensamguk.logic.world.*

sealed interface HwihaMusterAssessment {
    data class Eligible(val destination: StrategicNodeRef.LandProvince,
        val corps: List<HwihaDeployedCorps>) : HwihaMusterAssessment
    data class Rejected(val reason: HwihaMilitaryFailure) : HwihaMusterAssessment
}

/** Redirects the owner's active commanded bugoks to the owner's current province. */
object HwihaMusterRules {
    fun assess(actorId: Int, projection: DeploymentProjection, topology: StrategicTopologySnapshot,
        metrics: LandMarchMetricSnapshot, worldMeta: Map<String, Any?>): HwihaMusterAssessment {
        fun reject(reason: HwihaMilitaryFailure) = HwihaMusterAssessment.Rejected(reason)
        if (projection.profile != RuleProfile.HWIHA) return reject(HwihaMilitaryFailure.WRONG_RULE_PROFILE)
        val owner = projection.people.singleOrNull { it.id == actorId }
            ?: return reject(HwihaMilitaryFailure.ACTOR_NOT_FOUND)
        val destination = owner.node as? StrategicNodeRef.LandProvince
            ?: return reject(HwihaMilitaryFailure.POSITION_UNAVAILABLE)
        if (owner.inBattle) return reject(HwihaMilitaryFailure.BATTLE_PENDING)
        val owned = projection.deployed.filter { it.ownerGeneralId == actorId }.sortedBy { it.commanderGeneralId }
        if (owned.isEmpty()) return reject(HwihaMilitaryFailure.NO_COMMANDED_CORPS)
        val toMove = owned.filter { corps ->
            projection.people.singleOrNull { it.id == corps.commanderGeneralId }?.node != destination
        }
        if (toMove.isEmpty()) return reject(HwihaMilitaryFailure.NO_GATHER_TARGET)
        try {
            val edges = LandPassageState.read(worldMeta, topology)
                ?: return reject(HwihaMilitaryFailure.STATE_UNAVAILABLE)
            if (HwihaMarchReactions.presence(worldMeta).let {
                    it == HwihaMarchReactions.Presence.MISSING || it == HwihaMarchReactions.Presence.MALFORMED })
                return reject(HwihaMilitaryFailure.STATE_UNAVAILABLE)
            for (corps in toMove) {
                val active = HwihaDeploymentRules.assessActive(corps, projection)
                if (active !is DeploymentAssessment.Eligible) return reject(HwihaMilitaryFailure.STATE_UNAVAILABLE)
                val commander = active.commander
                if (commander.inBattle) return reject(HwihaMilitaryFailure.CORPS_BUSY)
                val start = commander.node as? StrategicNodeRef.LandProvince
                    ?: return reject(HwihaMilitaryFailure.POSITION_UNAVAILABLE)
                val path = StrategicPathResolver.resolveLandMarch(topology,
                    StrategicPathRequest(start, destination, 1), edges, metrics)
                if (path !is LandMarchPathResult.Resolved) return reject(HwihaMilitaryFailure.ROUTE_UNAVAILABLE)
            }
            return HwihaMusterAssessment.Eligible(destination, toMove)
        } catch (_: IllegalArgumentException) { return reject(HwihaMilitaryFailure.STATE_UNAVAILABLE) }
    }
}
