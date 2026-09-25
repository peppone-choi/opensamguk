package opensamguk.logic.input

import opensamguk.logic.domestic.FieldRequest
import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.domestic.FieldAssessment
import opensamguk.logic.domestic.FieldRules

import opensamguk.logic.domestic.DomesticProjection

import opensamguk.logic.economy.HwihaResources

enum class HwihaMilitaryFailure(val message: String) {
    WRONG_RULE_PROFILE("이 세계에서는 휘하 군사 행동을 사용할 수 없습니다."),
    INVALID_INPUT("군사 행동 인자를 확인할 수 없습니다."),
    ACTOR_NOT_FOUND("행동할 장수를 찾을 수 없습니다."),
    POSITION_UNAVAILABLE("장수의 현재 육상 위치를 확인할 수 없습니다."),
    BATTLE_PENDING("조우 처리가 끝나야 군사 행동을 할 수 있습니다."),
    CORPS_DEPLOYED("출전 중인 부대의 지휘관은 도시 병력 행동을 할 수 없습니다."),
    COUNTY_UNAVAILABLE("현재 위치에 행정 縣이 없습니다."),
    FOREIGN_COUNTY("현재 縣이 본인 세력의 소유가 아닙니다."),
    STATE_UNAVAILABLE("현재 縣의 상태를 확인할 수 없습니다."),
    WAREHOUSE_NOT_READY("縣 창고를 확인할 수 없습니다."),
    INSUFFICIENT_STOCK("縣 창고의 물자가 모자랍니다."),
    NO_HOUSEHOLDS("모집할 호구가 없습니다."),
    NO_CITY_TROOPS("도시 소유 병력이 없습니다."),
    ALREADY_MAX("훈련·사기가 이미 최대치입니다."),
    POPULATION_FULL("호구가 상한에 도달해 병력을 소집해제할 수 없습니다."),
    BESIEGED("포위 중인 縣에서는 도시 병력 행동을 할 수 없습니다."),
    NO_COMMANDED_CORPS("집결시킬 지휘 중인 부곡이 없습니다."),
    NO_GATHER_TARGET("현재 위치로 집결시킬 군단이 없습니다."),
    CORPS_BUSY("조우 중인 군단은 집결 명령을 받을 수 없습니다."),
    ROUTE_UNAVAILABLE("현재 위치로 통행 가능한 군단 경로가 없습니다."),
    ALREADY_PROCESSED("이 순에는 이미 군사 행동을 실행했습니다."),
}

data class HwihaCityMilitaryPlan(val countyId: Int, val population: Int, val troops: Int,
    val condition: HwihaCityMilitaryState, val debit: HwihaResources)

sealed interface HwihaCityMilitaryAssessment {
    data class Eligible(val plan: HwihaCityMilitaryPlan) : HwihaCityMilitaryAssessment
    data class Rejected(val reason: HwihaMilitaryFailure) : HwihaCityMilitaryAssessment
}

/** One precheck for API and engine; county identity comes from the general's spatial pin. */
object HwihaMilitaryRules {
    fun assessCity(request: HwihaMilitaryRequest, projection: DomesticProjection,
        population: Int?, populationMax: Int?, troops: Int?, condition: HwihaCityMilitaryState?, stock: HwihaResources?,
        design: HwihaMilitaryDesign): HwihaCityMilitaryAssessment {
        fun reject(reason: HwihaMilitaryFailure) = HwihaCityMilitaryAssessment.Rejected(reason)
        if (request.inputId == HwihaMilitaryInput.MUSTER || request.inputId !in HwihaMilitaryInput.INPUT_IDS)
            return reject(HwihaMilitaryFailure.INVALID_INPUT)
        // The shared field geography rule owns county lookup; its input vocabulary is domestic-only.
        val shared = FieldRules.assess(FieldRequest(request.actorId, FieldInput.FARM), projection)
        if (shared is FieldAssessment.Rejected)
            return reject(HwihaMilitaryFailure.valueOf(shared.reason.name))
        val county = (shared as FieldAssessment.Eligible).county
        if (county.id in projection.activeSiegeCountyIds) return reject(HwihaMilitaryFailure.BESIEGED)
        if (population == null || populationMax == null || troops == null || condition == null ||
            population < 0 || populationMax < population || troops < 0)
            return reject(HwihaMilitaryFailure.STATE_UNAVAILABLE)
        return try {
            val next = when (request.inputId) {
                HwihaMilitaryInput.CONSCRIPT, HwihaMilitaryInput.RAISE_VOLUNTEERS -> {
                    if (population == 0) return reject(HwihaMilitaryFailure.NO_HOUSEHOLDS)
                    val rate = if (request.inputId == HwihaMilitaryInput.CONSCRIPT)
                        design.conscriptHouseholdPermille else design.volunteerHouseholdPermille
                    val recruited = maxOf(1, (population.toLong() * rate / 1000).toInt()).coerceAtMost(population)
                    val grain = Math.multiplyExact(recruited.toLong(), design.grainPerTroop)
                    val money = if (request.inputId == HwihaMilitaryInput.RAISE_VOLUNTEERS)
                        Math.multiplyExact(recruited.toLong(), design.moneyPerVolunteer) else 0L
                    HwihaCityMilitaryPlan(county.id, population - recruited, Math.addExact(troops, recruited),
                        condition, HwihaResources(money = money, grain = grain))
                }
                HwihaMilitaryInput.TRAIN -> {
                    if (troops == 0) return reject(HwihaMilitaryFailure.NO_CITY_TROOPS)
                    if (condition.training == 100) return reject(HwihaMilitaryFailure.ALREADY_MAX)
                    HwihaCityMilitaryPlan(county.id, population, troops,
                        condition.copy(training = (condition.training + design.trainingGain).coerceAtMost(100)), HwihaResources())
                }
                HwihaMilitaryInput.BOOST_MORALE -> {
                    if (troops == 0) return reject(HwihaMilitaryFailure.NO_CITY_TROOPS)
                    if (condition.morale == 100) return reject(HwihaMilitaryFailure.ALREADY_MAX)
                    HwihaCityMilitaryPlan(county.id, population, troops,
                        condition.copy(morale = (condition.morale + design.moraleGain).coerceAtMost(100)), HwihaResources())
                }
                HwihaMilitaryInput.DEMOBILIZE -> {
                    if (troops == 0) return reject(HwihaMilitaryFailure.NO_CITY_TROOPS)
                    val headroom = populationMax - population
                    if (headroom == 0) return reject(HwihaMilitaryFailure.POPULATION_FULL)
                    val released = maxOf(1, (troops.toLong() * design.demobilizeTroopPermille / 1000).toInt())
                        .coerceAtMost(troops).coerceAtMost(headroom)
                    HwihaCityMilitaryPlan(county.id, Math.addExact(population, released), troops - released,
                        condition, HwihaResources())
                }
                else -> return reject(HwihaMilitaryFailure.INVALID_INPUT)
            }
            if (next.debit != HwihaResources()) {
                if (stock == null) return reject(HwihaMilitaryFailure.WAREHOUSE_NOT_READY)
                if (stock.debit(next.debit) == null) return reject(HwihaMilitaryFailure.INSUFFICIENT_STOCK)
            }
            HwihaCityMilitaryAssessment.Eligible(next)
        } catch (_: ArithmeticException) { reject(HwihaMilitaryFailure.STATE_UNAVAILABLE) }
    }
}
