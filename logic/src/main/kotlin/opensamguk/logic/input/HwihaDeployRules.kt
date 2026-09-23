package opensamguk.logic.input

import opensamguk.logic.world.*

/** Shared admission/execution checks. A new order must have a currently passable land route. */
object HwihaDeployRules {
    fun assess(request: DeployInput, state: DeploymentProjection, topology: StrategicTopologySnapshot,
        worldMeta: Map<String, Any?>, metrics: LandMarchMetricSnapshot): DeploymentAssessment {
        val relationship = HwihaDeploymentRules.assess(request.deploymentRequest(), state)
        if (relationship !is DeploymentAssessment.Eligible) return relationship
        if (!topology.containsNode(request.destination)) return DeploymentAssessment.Rejected(DeploymentFailure.INVALID_DESTINATION)
        return try {
            val edges = HwihaLandPassageState.read(worldMeta, topology)
            // 기록이 쌓인(PENDING) 반응 목록은 출병 입력을 막지 않는다 — 진입 판정이 반응 정책으로 따로 본다.
            if (edges == null || HwihaMarchReactions.presence(worldMeta).let {
                    it == HwihaMarchReactions.Presence.MISSING || it == HwihaMarchReactions.Presence.MALFORMED })
                DeploymentAssessment.Rejected(DeploymentFailure.STATE_UNAVAILABLE)
            else if (StrategicPathResolver.resolveLandMarch(topology,
                StrategicPathRequest(relationship.commander.node!!, request.destination, 1), edges, metrics) !is LandMarchPathResult.Resolved)
                DeploymentAssessment.Rejected(DeploymentFailure.NO_ROUTE)
            else relationship
        } catch (_: IllegalArgumentException) { DeploymentAssessment.Rejected(DeploymentFailure.STATE_UNAVAILABLE) }
    }

    fun reason(reason: DeploymentFailure): String = when (reason) {
        DeploymentFailure.WRONG_RULE_PROFILE -> "이 세계에서는 새 출병 입력을 사용할 수 없습니다."
        DeploymentFailure.INVALID_INPUT -> "출병 입력이 올바르지 않습니다."
        DeploymentFailure.NO_ROUTE -> "목적지까지 통행 가능한 육상 경로가 없습니다."
        DeploymentFailure.INVALID_DESTINATION -> "출병 목적지를 확인할 수 없습니다."
        DeploymentFailure.OWNER_UNAVAILABLE -> "출병할 장수를 확인할 수 없습니다."
        DeploymentFailure.COMMANDER_UNAVAILABLE -> "지휘할 장수를 확인할 수 없습니다."
        DeploymentFailure.DIFFERENT_NATION -> "출병 장수와 지휘자의 소속이 다릅니다."
        DeploymentFailure.POSITION_UNAVAILABLE -> "현재 육상 위치를 확인할 수 없습니다."
        DeploymentFailure.MUST_ASSEMBLE -> "출병할 지휘자는 같은 지역에 집결해야 합니다."
        DeploymentFailure.BATTLE_PENDING -> "진행 중인 조우가 끝나야 출병할 수 있습니다."
        DeploymentFailure.UNIT_UNAVAILABLE -> "선택한 부대의 소유권이나 병력을 확인할 수 없습니다."
        DeploymentFailure.COMMANDER_CHANGED -> "선택한 부대의 지휘관이 바뀌었습니다."
        DeploymentFailure.ALREADY_DEPLOYED -> "이미 출전한 지휘자나 부대입니다."
        DeploymentFailure.STATE_UNAVAILABLE -> "출병에 필요한 지도·군사 상태를 확인할 수 없습니다."
    }
}
