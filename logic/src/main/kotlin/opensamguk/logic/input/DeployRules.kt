package opensamguk.logic.input

import opensamguk.logic.world.*

/** Shared admission/execution checks. A new order must have a currently passable land route. */
object DeployRules {
    fun assess(request: DeployInput, state: DeploymentProjection, topology: StrategicTopologySnapshot,
        worldMeta: Map<String, Any?>, metrics: LandMarchMetricSnapshot,
        passageOverride: StrategicEdgeStateSnapshot? = null): DeploymentAssessment =
        assessRoute(request, state, topology, worldMeta, metrics, passageOverride).assessment

    data class RouteAssessment(val assessment: DeploymentAssessment, val path: ResolvedLandMarchPath? = null)

    fun assessRoute(request: DeployInput, state: DeploymentProjection, topology: StrategicTopologySnapshot,
        worldMeta: Map<String, Any?>, metrics: LandMarchMetricSnapshot,
        passageOverride: StrategicEdgeStateSnapshot? = null): RouteAssessment =
        assessRoutes(listOf(request), state, topology, worldMeta, metrics, passageOverride).single()

    /** Options and admission share relationship, destination and passage checks in the same order. */
    fun assessRoutes(requests: List<DeployInput>, state: DeploymentProjection, topology: StrategicTopologySnapshot,
        worldMeta: Map<String, Any?>, metrics: LandMarchMetricSnapshot,
        passageOverride: StrategicEdgeStateSnapshot? = null): List<RouteAssessment> {
        val relationships = hashMapOf<DeploymentRequest, DeploymentAssessment>()
        val checks = requests.map { request ->
            val relationship = relationships.getOrPut(request.deploymentRequest()) {
                DeploymentRules.assess(request.deploymentRequest(), state)
            }
            when {
                relationship !is DeploymentAssessment.Eligible -> RouteAssessment(relationship)
                !topology.containsNode(request.destination) ->
                    RouteAssessment(DeploymentAssessment.Rejected(DeploymentFailure.INVALID_DESTINATION))
                else -> RouteAssessment(relationship)
            }
        }.toMutableList()
        val pending = requests.indices.filter { checks[it].assessment is DeploymentAssessment.Eligible }
        if (pending.isEmpty()) return checks
        try {
            val edges = passageOverride ?: LandPassageState.read(worldMeta, topology)
            // PENDING reactions retain the same admission semantics as a single destination.
            if (edges == null || MarchReactions.presence(worldMeta).let {
                    it == MarchReactions.Presence.MISSING || it == MarchReactions.Presence.MALFORMED }) {
                pending.forEach { checks[it] = RouteAssessment(
                    DeploymentAssessment.Rejected(DeploymentFailure.STATE_UNAVAILABLE)) }
            } else {
                val routes = StrategicPathResolver.resolveLandMarches(topology, pending.map { index ->
                    val relationship = checks[index].assessment as DeploymentAssessment.Eligible
                    StrategicPathRequest(relationship.commander.node!!, requests[index].destination, 1)
                }, edges, metrics)
                pending.zip(routes).forEach { (index, route) ->
                    checks[index] = when (route) {
                        is LandMarchPathResult.Resolved -> RouteAssessment(checks[index].assessment, route.path)
                        is LandMarchPathResult.Denied ->
                            RouteAssessment(DeploymentAssessment.Rejected(DeploymentFailure.NO_ROUTE))
                    }
                }
            }
        } catch (_: IllegalArgumentException) {
            pending.forEach { checks[it] = RouteAssessment(
                DeploymentAssessment.Rejected(DeploymentFailure.STATE_UNAVAILABLE)) }
        }
        return checks
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
