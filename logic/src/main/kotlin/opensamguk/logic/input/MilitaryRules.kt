package opensamguk.logic.input

import opensamguk.logic.domestic.FieldRequest
import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.domestic.FieldAssessment
import opensamguk.logic.domestic.FieldRules

import opensamguk.logic.domestic.DomesticProjection

import opensamguk.logic.economy.Resources

enum class MilitaryFailure(val message: String) {
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

data class CityMilitaryPlan(val countyId: Int, val population: Int, val troops: Int,
    val condition: CityMilitaryState, val debit: Resources)

sealed interface CityMilitaryAssessment {
    data class Eligible(val plan: CityMilitaryPlan) : CityMilitaryAssessment
    data class Rejected(val reason: MilitaryFailure) : CityMilitaryAssessment
}

/** One precheck for API and engine; county identity comes from the general's spatial pin. */
object MilitaryRules {
    fun assessCity(request: MilitaryRequest, projection: DomesticProjection,
        population: Int?, populationMax: Int?, troops: Int?, condition: CityMilitaryState?, stock: Resources?,
        design: MilitaryDesign): CityMilitaryAssessment {
        fun reject(reason: MilitaryFailure) = CityMilitaryAssessment.Rejected(reason)
        if (request.inputId == MilitaryInput.MUSTER || request.inputId !in MilitaryInput.INPUT_IDS)
            return reject(MilitaryFailure.INVALID_INPUT)
        // The shared field geography rule owns county lookup; its input vocabulary is domestic-only.
        val shared = FieldRules.assess(FieldRequest(request.actorId, FieldInput.FARM), projection)
        if (shared is FieldAssessment.Rejected)
            return reject(MilitaryFailure.valueOf(shared.reason.name))
        val county = (shared as FieldAssessment.Eligible).county
        if (county.id in projection.activeSiegeCountyIds) return reject(MilitaryFailure.BESIEGED)
        if (population == null || populationMax == null || troops == null || condition == null ||
            population < 0 || populationMax < population || troops < 0)
            return reject(MilitaryFailure.STATE_UNAVAILABLE)
        return try {
            val next = when (request.inputId) {
                MilitaryInput.CONSCRIPT, MilitaryInput.RAISE_VOLUNTEERS -> {
                    if (population == 0) return reject(MilitaryFailure.NO_HOUSEHOLDS)
                    val rate = if (request.inputId == MilitaryInput.CONSCRIPT)
                        design.conscriptHouseholdPermille else design.volunteerHouseholdPermille
                    val recruited = maxOf(1, (population.toLong() * rate / 1000).toInt()).coerceAtMost(population)
                    val grain = Math.multiplyExact(recruited.toLong(), design.grainPerTroop)
                    val money = if (request.inputId == MilitaryInput.RAISE_VOLUNTEERS)
                        Math.multiplyExact(recruited.toLong(), design.moneyPerVolunteer) else 0L
                    CityMilitaryPlan(county.id, population - recruited, Math.addExact(troops, recruited),
                        condition, Resources(money = money, grain = grain))
                }
                MilitaryInput.TRAIN -> {
                    if (troops == 0) return reject(MilitaryFailure.NO_CITY_TROOPS)
                    if (condition.training == 100) return reject(MilitaryFailure.ALREADY_MAX)
                    CityMilitaryPlan(county.id, population, troops,
                        condition.copy(training = (condition.training + design.trainingGain).coerceAtMost(100)), Resources())
                }
                MilitaryInput.BOOST_MORALE -> {
                    if (troops == 0) return reject(MilitaryFailure.NO_CITY_TROOPS)
                    if (condition.morale == 100) return reject(MilitaryFailure.ALREADY_MAX)
                    CityMilitaryPlan(county.id, population, troops,
                        condition.copy(morale = (condition.morale + design.moraleGain).coerceAtMost(100)), Resources())
                }
                MilitaryInput.DEMOBILIZE -> {
                    if (troops == 0) return reject(MilitaryFailure.NO_CITY_TROOPS)
                    val headroom = populationMax - population
                    if (headroom == 0) return reject(MilitaryFailure.POPULATION_FULL)
                    val released = maxOf(1, (troops.toLong() * design.demobilizeTroopPermille / 1000).toInt())
                        .coerceAtMost(troops).coerceAtMost(headroom)
                    CityMilitaryPlan(county.id, Math.addExact(population, released), troops - released,
                        condition, Resources())
                }
                else -> return reject(MilitaryFailure.INVALID_INPUT)
            }
            if (next.debit != Resources()) {
                if (stock == null) return reject(MilitaryFailure.WAREHOUSE_NOT_READY)
                if (stock.debit(next.debit) == null) return reject(MilitaryFailure.INSUFFICIENT_STOCK)
            }
            CityMilitaryAssessment.Eligible(next)
        } catch (_: ArithmeticException) { reject(MilitaryFailure.STATE_UNAVAILABLE) }
    }
}
