package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticProjection

enum class PersonalFailure(val message: String) {
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

sealed interface PersonalAssessment {
    data class Eligible(val actor: DomesticPerson,
        val condition: PersonalTravelCondition) : PersonalAssessment
    data class Rejected(val reason: PersonalFailure) : PersonalAssessment
}

/** Shared precheck for player reservation, options and immediate execution. */
object PersonalRules {
    fun assess(request: PersonalRequest, state: DomesticProjection): PersonalAssessment {
        fun reject(reason: PersonalFailure) = PersonalAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return reject(PersonalFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.inputId !in PersonalInput.FIELD_IDS ||
            (request.inputId == PersonalInput.SELF_TRAIN) != (request.trainingStat != null))
            return reject(PersonalFailure.INVALID_INPUT)
        val actor = state.person(request.actorId) ?: return reject(PersonalFailure.ACTOR_NOT_FOUND)
        if (actor.inBattle) return reject(PersonalFailure.BATTLE_PENDING)
        val node = actor.node ?: return reject(PersonalFailure.POSITION_UNAVAILABLE)
        if (state.landProvinceIds?.contains(node) != true) return reject(PersonalFailure.STATE_UNAVAILABLE)
        val condition = try { PersonalTravelCondition.read(actor.meta) ?: PersonalTravelCondition.INITIAL }
            catch (_: IllegalArgumentException) { return reject(PersonalFailure.STATE_UNAVAILABLE) }
        if (actor.injury !in 0..100) return reject(PersonalFailure.STATE_UNAVAILABLE)
        when (request.inputId) {
            PersonalInput.SELF_TRAIN -> {
                val value = when (request.trainingStat) {
                    TrainingStat.LEADERSHIP -> actor.leadership
                    TrainingStat.STRENGTH -> actor.strength
                    TrainingStat.INTELLIGENCE -> actor.intelligence
                    TrainingStat.POLITICS -> actor.politics
                    TrainingStat.CHARM -> actor.charm
                    null -> return reject(PersonalFailure.INVALID_INPUT)
                }
                if (value !in 0..100) return reject(PersonalFailure.STATE_UNAVAILABLE)
                if (value >= PersonalDesign.CANON.trainingStatCap) return reject(PersonalFailure.TRAINING_MAXED)
            }
            PersonalInput.RECUPERATE ->
                if (actor.injury == 0 && condition.fatigue == 0) return reject(PersonalFailure.ALREADY_HEALTHY)
        }
        return PersonalAssessment.Eligible(actor, condition)
    }
}
