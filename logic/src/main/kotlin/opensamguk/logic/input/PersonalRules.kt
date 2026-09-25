package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticProjection

enum class HwihaPersonalFailure(val message: String) {
    WRONG_RULE_PROFILE("이 월드에서는 개인 행동을 사용할 수 없습니다."),
    INVALID_INPUT("개인 행동 인자를 확인할 수 없습니다."),
    ACTOR_NOT_FOUND("장수를 찾을 수 없습니다."),
    POSITION_UNAVAILABLE("장수의 현재 육상 위치를 확인할 수 없습니다."),
    BATTLE_PENDING("조우 처리가 끝나야 개인 행동을 할 수 있습니다."),
    STATE_UNAVAILABLE("현재 장수 상태를 확인할 수 없습니다."),
    TRAINING_MAXED("선택한 능력은 더 단련할 수 없습니다."),
    ALREADY_HEALTHY("부상과 피로가 없어 요양할 필요가 없습니다."),
    ALREADY_PROCESSED("이 순에는 이미 개인 행동을 실행했습니다."),
}

sealed interface HwihaPersonalAssessment {
    data class Eligible(val actor: DomesticPerson,
        val condition: HwihaPersonalTravelCondition) : HwihaPersonalAssessment
    data class Rejected(val reason: HwihaPersonalFailure) : HwihaPersonalAssessment
}

/** Shared precheck for player reservation, options and immediate execution. */
object HwihaPersonalRules {
    fun assess(request: HwihaPersonalRequest, state: DomesticProjection): HwihaPersonalAssessment {
        fun reject(reason: HwihaPersonalFailure) = HwihaPersonalAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return reject(HwihaPersonalFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.inputId !in HwihaPersonalInput.FIELD_IDS ||
            (request.inputId == HwihaPersonalInput.SELF_TRAIN) != (request.trainingStat != null))
            return reject(HwihaPersonalFailure.INVALID_INPUT)
        val actor = state.person(request.actorId) ?: return reject(HwihaPersonalFailure.ACTOR_NOT_FOUND)
        if (actor.inBattle) return reject(HwihaPersonalFailure.BATTLE_PENDING)
        val node = actor.node ?: return reject(HwihaPersonalFailure.POSITION_UNAVAILABLE)
        if (state.landProvinceIds?.contains(node) != true) return reject(HwihaPersonalFailure.STATE_UNAVAILABLE)
        val condition = try { HwihaPersonalTravelCondition.read(actor.meta) ?: HwihaPersonalTravelCondition.INITIAL }
            catch (_: IllegalArgumentException) { return reject(HwihaPersonalFailure.STATE_UNAVAILABLE) }
        if (actor.injury !in 0..100) return reject(HwihaPersonalFailure.STATE_UNAVAILABLE)
        when (request.inputId) {
            HwihaPersonalInput.SELF_TRAIN -> {
                val value = when (request.trainingStat) {
                    HwihaTrainingStat.LEADERSHIP -> actor.leadership
                    HwihaTrainingStat.STRENGTH -> actor.strength
                    HwihaTrainingStat.INTELLIGENCE -> actor.intelligence
                    HwihaTrainingStat.POLITICS -> actor.politics
                    HwihaTrainingStat.CHARM -> actor.charm
                    null -> return reject(HwihaPersonalFailure.INVALID_INPUT)
                }
                if (value !in 0..100) return reject(HwihaPersonalFailure.STATE_UNAVAILABLE)
                if (value >= HwihaPersonalDesign.CANON.trainingStatCap) return reject(HwihaPersonalFailure.TRAINING_MAXED)
            }
            HwihaPersonalInput.RECUPERATE ->
                if (actor.injury == 0 && condition.fatigue == 0) return reject(HwihaPersonalFailure.ALREADY_HEALTHY)
        }
        return HwihaPersonalAssessment.Eligible(actor, condition)
    }
}
