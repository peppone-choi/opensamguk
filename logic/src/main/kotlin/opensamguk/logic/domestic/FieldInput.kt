package opensamguk.logic.domestic

import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.HwihaDeploymentState
import opensamguk.logic.input.HwihaFlatArguments
import opensamguk.logic.input.RuleProfile

/** A field action uses the acting general's saved position; the caller supplies no county, cost, or actor. */
data class FieldRequest(val actorId: Int, val inputId: String)

object FieldInput {
    const val FARM = "action.farm"
    const val COMMERCE = "action.commerce"
    const val FORTIFY = "action.fortify"
    const val REPAIR_WALL = "action.repairWall"
    const val SECURITY = "action.security"
    const val SETTLE = "action.settle"
    const val SELECT_RESIDENTS = "action.selectResidents"
    const val TOUR = "action.tour"
    val INPUT_IDS = linkedSetOf(FARM, COMMERCE, FORTIFY, REPAIR_WALL, SECURITY, SETTLE, SELECT_RESIDENTS, TOUR)

    fun parse(actorId: Int, inputId: String, rawJson: String?): FieldRequest? {
        if (actorId <= 0 || inputId !in INPUT_IDS || rawJson == null) return null
        return try {
            if (HwihaFlatArguments(rawJson).read().isNotEmpty()) null else FieldRequest(actorId, inputId)
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: FieldRequest): String {
        require(request.actorId > 0 && request.inputId in INPUT_IDS)
        return "{}"
    }
}

enum class FieldFailure(val message: String) {
    WRONG_RULE_PROFILE("이 세계에서는 휘하 현장 행동을 사용할 수 없습니다."),
    INVALID_INPUT("현장 행동 인자를 확인할 수 없습니다."),
    ACTOR_NOT_FOUND("행동할 장수를 찾을 수 없습니다."),
    POSITION_UNAVAILABLE("장수의 현재 육상 위치를 확인할 수 없습니다."),
    BATTLE_PENDING("조우 처리가 끝나야 현장 행동을 할 수 있습니다."),
    CORPS_DEPLOYED("출전 중인 부대의 지휘관은 현장 행동을 할 수 없습니다."),
    COUNTY_UNAVAILABLE("현재 위치에 행정 縣이 없습니다."),
    FOREIGN_COUNTY("현재 위치의 縣이 본인 세력의 소유가 아닙니다."),
    STATE_UNAVAILABLE("현재 縣의 소유·위치 상태를 확인할 수 없습니다."),
    WAREHOUSE_NOT_READY("縣 창고를 확인할 수 없습니다."),
    INSUFFICIENT_STOCK("縣 창고의 물자가 모자랍니다."),
    AT_CAPACITY("현재 縣 지표가 상한에 도달해 효과가 없습니다."),
    ALREADY_PROCESSED("이 순에는 이미 현장 행동을 실행했습니다."),
}

sealed interface FieldAssessment {
    data class Eligible(val county: DomesticCounty, val person: DomesticPerson) : FieldAssessment
    data class Rejected(val reason: FieldFailure) : FieldAssessment
}

/** Both admission and execution resolve the exact same county from spatial position, never general.cityId. */
object FieldRules {
    fun assess(request: FieldRequest, state: DomesticProjection): FieldAssessment {
        fun reject(reason: FieldFailure) = FieldAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return reject(FieldFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.inputId !in FieldInput.INPUT_IDS) return reject(FieldFailure.INVALID_INPUT)
        val person = state.person(request.actorId) ?: return reject(FieldFailure.ACTOR_NOT_FOUND)
        if (person.inBattle) return reject(FieldFailure.BATTLE_PENDING)
        val deployed = try { HwihaDeploymentState.read(person.meta)?.corps.orEmpty() }
            catch (_: IllegalArgumentException) { return reject(FieldFailure.STATE_UNAVAILABLE) }
        if (deployed.any { it.ownerGeneralId == person.id }) return reject(FieldFailure.CORPS_DEPLOYED)
        val node = person.node ?: return reject(FieldFailure.POSITION_UNAVAILABLE)
        if (state.landProvinceIds?.contains(node) != true) return reject(FieldFailure.STATE_UNAVAILABLE)
        val counties = state.counties.filter { it.provinceId == node }
        if (counties.isEmpty()) return reject(FieldFailure.COUNTY_UNAVAILABLE)
        if (counties.size != 1) return reject(FieldFailure.STATE_UNAVAILABLE)
        val county = counties.single()
        if (person.nationId <= 0 || county.nationId != person.nationId) return reject(FieldFailure.FOREIGN_COUNTY)
        return FieldAssessment.Eligible(county, person)
    }

    /** A single affordability calculation is used by both the API snapshot and the execution world. */
    fun assessEconomy(inputId: String, person: DomesticPerson, countyId: Int,
        levels: CountyLevels?, stock: HwihaResources?, design: DomesticDesign,
        hometown: Boolean = false): FieldEconomyAssessment {
        if (levels == null) return FieldEconomyAssessment.Rejected(FieldFailure.STATE_UNAVAILABLE)
        val stats = SeatStats(person.leadership, person.strength, person.intelligence, person.politics,
            person.charm, hometown)
        val outcome = try { DomesticEffects.applyDirect(design, inputId, levels, stats) }
            catch (_: IllegalArgumentException) { return FieldEconomyAssessment.Rejected(FieldFailure.INVALID_INPUT) }
            catch (_: ArithmeticException) { return FieldEconomyAssessment.Rejected(FieldFailure.STATE_UNAVAILABLE) }
        if (outcome.levels == levels && outcome.credit == HwihaResources())
            return FieldEconomyAssessment.Rejected(FieldFailure.AT_CAPACITY)
        if (outcome.debit != HwihaResources() || outcome.credit != HwihaResources()) {
            if (stock == null) return FieldEconomyAssessment.Rejected(FieldFailure.WAREHOUSE_NOT_READY)
            if (stock.debit(outcome.debit) == null)
                return FieldEconomyAssessment.Rejected(FieldFailure.INSUFFICIENT_STOCK)
        }
        return FieldEconomyAssessment.Eligible(countyId, outcome)
    }
}

sealed interface FieldEconomyAssessment {
    data class Eligible(val countyId: Int, val outcome: PolicyOutcome) : FieldEconomyAssessment
    data class Rejected(val reason: FieldFailure) : FieldEconomyAssessment
}
