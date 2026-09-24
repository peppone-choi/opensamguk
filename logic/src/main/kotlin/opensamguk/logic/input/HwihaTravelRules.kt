package opensamguk.logic.input

import opensamguk.logic.world.*

data class HwihaTravelSnapshot(
    val profile: RuleProfile,
    val actorExists: Boolean,
    val actorNode: StrategicNodeRef?,
    val inBattle: Boolean,
    val commandsCorps: Boolean,
)

enum class HwihaTravelFailure(val message: String) {
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
}

sealed interface HwihaTravelAssessment {
    data class Eligible(val path: ResolvedLandMarchPath) : HwihaTravelAssessment
    data class Rejected(val reason: HwihaTravelFailure) : HwihaTravelAssessment
}

/** Both options/admission and the personal-turn executor use this exact assessment. */
object HwihaTravelRules {
    fun assess(request: HwihaTravelRequest, destination: StrategicNodeRef.LandProvince?,
        snapshot: HwihaTravelSnapshot, topology: StrategicTopologySnapshot,
        metrics: LandMarchMetricSnapshot, worldMeta: Map<String, Any?>): HwihaTravelAssessment {
        fun reject(reason: HwihaTravelFailure) = HwihaTravelAssessment.Rejected(reason)
        if (snapshot.profile != RuleProfile.HWIHA) return reject(HwihaTravelFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.inputId !in HwihaTravelInput.INPUT_IDS || destination == null)
            return reject(HwihaTravelFailure.INVALID_INPUT)
        if (request.inputId != HwihaTravelInput.RETURN && request.destination != destination)
            return reject(HwihaTravelFailure.INVALID_INPUT)
        if (!snapshot.actorExists) return reject(HwihaTravelFailure.ACTOR_NOT_FOUND)
        val origin = snapshot.actorNode as? StrategicNodeRef.LandProvince
            ?: return reject(HwihaTravelFailure.POSITION_UNAVAILABLE)
        if (snapshot.inBattle) return reject(HwihaTravelFailure.BATTLE_PENDING)
        if (snapshot.commandsCorps) return reject(HwihaTravelFailure.CORPS_DEPLOYED)
        if (!topology.containsNode(destination)) return reject(HwihaTravelFailure.INVALID_DESTINATION)
        if (origin == destination) return reject(HwihaTravelFailure.ALREADY_THERE)
        return try {
            val passage = HwihaLandPassageState.read(worldMeta, topology)
            if (passage == null || HwihaMarchReactions.presence(worldMeta) in setOf(
                    HwihaMarchReactions.Presence.MISSING, HwihaMarchReactions.Presence.MALFORMED))
                return reject(HwihaTravelFailure.STATE_UNAVAILABLE)
            when (val route = StrategicPathResolver.resolveLandMarch(topology,
                StrategicPathRequest(origin, destination, 1), passage, metrics)) {
                is LandMarchPathResult.Resolved -> HwihaTravelAssessment.Eligible(route.path)
                is LandMarchPathResult.Denied -> reject(HwihaTravelFailure.NO_ROUTE)
            }
        } catch (_: IllegalArgumentException) { reject(HwihaTravelFailure.STATE_UNAVAILABLE) }
    }
}
