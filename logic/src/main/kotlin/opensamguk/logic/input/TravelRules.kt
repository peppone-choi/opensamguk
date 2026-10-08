package opensamguk.logic.input

import opensamguk.logic.world.*
import opensamguk.logic.travel.PersonalReturnStop

data class TravelSnapshot(
    val profile: RuleProfile,
    val actorExists: Boolean,
    val actorNode: StrategicNodeRef?,
    val inBattle: Boolean,
    val commandsCorps: Boolean,
    val hostileNationIds: Set<Int>,
    val actorMeta: Map<String, Any?>,
)

enum class TravelFailure(val message: String) {
    WRONG_RULE_PROFILE("이 세계에서는 휘하 이동 입력을 사용할 수 없습니다."),
    INVALID_INPUT("이동 목적지를 확인할 수 없습니다."),
    ACTOR_NOT_FOUND("이동할 장수를 찾을 수 없습니다."),
    POSITION_UNAVAILABLE("장수의 현재 육상 위치를 확인할 수 없습니다."),
    BATTLE_PENDING("조우 처리가 끝나야 이동할 수 있습니다."),
    CORPS_DEPLOYED("출전 중인 부대는 개인 이동으로 움직일 수 없습니다."),
    INVALID_DESTINATION("지도에 없는 육상 목적지입니다."),
    NO_RETURN_ASSIGNMENT("발령된 근무 城이 없어 귀환 목적지를 정할 수 없습니다."),
    ALREADY_THERE("이미 목적지에 있습니다."),
    STATE_UNAVAILABLE("지도·통행·반응 상태를 확인할 수 없습니다."),
    NO_ROUTE("목적지까지 통행 가능한 육상 경로가 없습니다."),
    FORCED_ROUTE_TOO_LONG("강행 경로의 실제 거리가 허용 범위를 넘습니다."),
    FORCED_DURATION_EXCEEDED("강행 도착 예상이 허용 순 수를 넘습니다."),
    FORCED_MARCH_EXHAUSTED("피로·사기가 부족해 강행 비용을 치를 수 없습니다."),
    TRAVEL_POLICY_CHANGED("이동 규칙이 바뀌어 기존 주문을 보류했습니다. 현재 위치에서 다시 예약하세요."),
}

sealed interface TravelAssessment {
    data class Eligible(val path: ResolvedLandMarchPath,
        val forcedPreview: PersonalForcedMarchPreview? = null) : TravelAssessment
    data class Rejected(val reason: TravelFailure) : TravelAssessment
}

enum class DestinationReachability { THIS_TURN, MULTI_TURN, UNAVAILABLE }

data class MarchDestinationEstimate(val reachability: DestinationReachability, val distanceMm: Long,
    val costMm: Long, val estimatedTurns: Long) {
    val arrivesThisTurn: Boolean get() = reachability == DestinationReachability.THIS_TURN

    companion object {
        fun of(path: ResolvedLandMarchPath, metrics: LandMarchMetricSnapshot, budgetMm: Long): MarchDestinationEstimate {
            require(budgetMm > 0 && path.metricHash == metrics.contentHash)
            val distance = path.edgeIds.fold(0L) { total, id ->
                Math.addExact(total, metrics.edgesById.getValue(id).distanceMm)
            }
            val turns = path.totalCostMm / budgetMm + if (path.totalCostMm % budgetMm == 0L) 0 else 1
            return MarchDestinationEstimate(if (turns <= 1) DestinationReachability.THIS_TURN
                else DestinationReachability.MULTI_TURN, distance, path.totalCostMm, turns)
        }
    }
}

/** Both options/admission and the personal-turn executor use this exact assessment. */
object TravelRules {
    const val CAPTIVE_REASON = "구금된 장수는 개인 순 행동을 예약할 수 없습니다."

    fun actorFailure(snapshot: TravelSnapshot): TravelFailure? = when {
        !snapshot.actorExists -> TravelFailure.ACTOR_NOT_FOUND
        CaptiveState.META_KEY in snapshot.actorMeta -> TravelFailure.STATE_UNAVAILABLE
        else -> try { PersonalReturnStop.read(snapshot.actorMeta); null }
            catch (_: IllegalArgumentException) { TravelFailure.STATE_UNAVAILABLE }
    }

    fun assess(request: TravelRequest, destination: StrategicNodeRef.LandProvince?,
        snapshot: TravelSnapshot, topology: StrategicTopologySnapshot,
        metrics: LandMarchMetricSnapshot, worldMeta: Map<String, Any?>): TravelAssessment =
        assessMany(listOf(request to destination), snapshot, topology, metrics, worldMeta).single()

    /** Reuse route work only within this snapshot; admission/execution share the same ordered checks. */
    fun assessMany(requests: List<Pair<TravelRequest, StrategicNodeRef.LandProvince?>>,
        snapshot: TravelSnapshot, topology: StrategicTopologySnapshot,
        metrics: LandMarchMetricSnapshot, worldMeta: Map<String, Any?>): List<TravelAssessment> {
        val results = requests.map { (request, destination) ->
            failure(request, destination, snapshot, topology)?.let(TravelAssessment::Rejected)
        }.toMutableList<TravelAssessment?>()
        val eligible = results.indices.filter { results[it] == null }
        if (eligible.isEmpty()) return results.map { requireNotNull(it) }
        val origin = snapshot.actorNode as StrategicNodeRef.LandProvince
        val routes = try {
            val passage = LandPassageState.read(worldMeta, topology)
            require(passage != null && MarchReactions.presence(worldMeta) !in setOf(
                MarchReactions.Presence.MISSING, MarchReactions.Presence.MALFORMED))
            val nationPassage = RoadFortState.forNation(passage, RoadFortState.read(worldMeta),
                snapshot.hostileNationIds)
            val moves = if (eligible.any { requests[it].first.inputId in setOf(TravelInput.MOVE, TravelInput.FORCED_MARCH) })
                PersonalMovePath.from(origin, topology, metrics, nationPassage) else emptyMap()
            val marches = eligible.filter { requests[it].first.inputId != TravelInput.MOVE }
            val marchRoutes = StrategicPathResolver.resolveLandMarches(topology, marches.map { index ->
                StrategicPathRequest(origin, requireNotNull(requests[index].second), 1)
            }, nationPassage, metrics)
            val routesByIndex = marches.zip(marchRoutes).toMap()
            eligible.map { index ->
                val route = if (requests[index].first.inputId == TravelInput.MOVE) {
                    moves[requireNotNull(requests[index].second).id]?.let(LandMarchPathResult::Resolved)
                        ?: LandMarchPathResult.Denied(PathDenialCode.NO_LAND_CONNECTION)
                } else routesByIndex.getValue(index)
                if (requests[index].first.inputId == TravelInput.FORCED_MARCH) {
                    val condition = PersonalTravelCondition.read(snapshot.actorMeta) ?: PersonalTravelCondition.INITIAL
                    return@map PersonalForcedMarchPolicy.assess(route,
                        moves[requireNotNull(requests[index].second).id], metrics, condition)
                }
                when (route) {
                    is LandMarchPathResult.Resolved -> TravelAssessment.Eligible(
                        if (requests[index].first.inputId == TravelInput.RETURN)
                            TravelReturn.firstStep(route.path, metrics) else route.path)
                    is LandMarchPathResult.Denied -> TravelAssessment.Rejected(TravelFailure.NO_ROUTE)
                }
            }
        } catch (_: IllegalArgumentException) {
            eligible.map { TravelAssessment.Rejected(TravelFailure.STATE_UNAVAILABLE) }
        }
        eligible.forEachIndexed { index, requestIndex -> results[requestIndex] = routes[index] }
        return results.map { requireNotNull(it) }
    }

    private fun failure(request: TravelRequest, destination: StrategicNodeRef.LandProvince?,
        snapshot: TravelSnapshot, topology: StrategicTopologySnapshot): TravelFailure? {
        if (snapshot.profile != RuleProfile.HWIHA) return TravelFailure.WRONG_RULE_PROFILE
        if (request.actorId <= 0 || request.inputId !in TravelInput.INPUT_IDS)
            return TravelFailure.INVALID_INPUT
        actorFailure(snapshot)?.let { return it }
        if (destination == null) return TravelFailure.INVALID_INPUT
        if (request.inputId != TravelInput.RETURN && request.destination != destination)
            return TravelFailure.INVALID_INPUT
        val origin = snapshot.actorNode as? StrategicNodeRef.LandProvince
            ?: return TravelFailure.POSITION_UNAVAILABLE
        if (snapshot.inBattle) return TravelFailure.BATTLE_PENDING
        if (snapshot.commandsCorps) return TravelFailure.CORPS_DEPLOYED
        if (!topology.containsNode(destination)) return TravelFailure.INVALID_DESTINATION
        if (origin == destination) return TravelFailure.ALREADY_THERE
        if (request.inputId == TravelInput.FORCED_MARCH) try {
            PersonalTravelCondition.read(snapshot.actorMeta)
        } catch (_: IllegalArgumentException) { return TravelFailure.STATE_UNAVAILABLE }
        return null
    }
}
