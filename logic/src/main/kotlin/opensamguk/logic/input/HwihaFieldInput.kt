package opensamguk.logic.input

import opensamguk.logic.economy.HwihaResources

/** A field action uses the acting general's saved position; the caller supplies no county, cost, or actor. */
data class HwihaFieldRequest(val actorId: Int, val inputId: String)

object HwihaFieldInput {
    const val FARM = "action.farm"
    const val COMMERCE = "action.commerce"
    const val FORTIFY = "action.fortify"
    const val REPAIR_WALL = "action.repairWall"
    const val SECURITY = "action.security"
    const val SETTLE = "action.settle"
    const val SELECT_RESIDENTS = "action.selectResidents"
    const val TOUR = "action.tour"
    val INPUT_IDS = linkedSetOf(FARM, COMMERCE, FORTIFY, REPAIR_WALL, SECURITY, SETTLE, SELECT_RESIDENTS, TOUR)

    fun parse(actorId: Int, inputId: String, rawJson: String?): HwihaFieldRequest? {
        if (actorId <= 0 || inputId !in INPUT_IDS || rawJson == null) return null
        return try {
            if (HwihaFlatArguments(rawJson).read().isNotEmpty()) null else HwihaFieldRequest(actorId, inputId)
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: HwihaFieldRequest): String {
        require(request.actorId > 0 && request.inputId in INPUT_IDS)
        return "{}"
    }
}

enum class HwihaFieldFailure(val message: String) {
    WRONG_RULE_PROFILE("이 세계에서는 휘하 현장 행동을 사용할 수 없습니다."),
    INVALID_INPUT("현장 행동 인자를 확인할 수 없습니다."),
    ACTOR_NOT_FOUND("행동할 장수를 찾을 수 없습니다."),
    POSITION_UNAVAILABLE("장수의 현재 육상 위치를 확인할 수 없습니다."),
    BATTLE_PENDING("조우 처리가 끝나야 현장 행동을 할 수 있습니다."),
    COUNTY_UNAVAILABLE("현재 위치에 행정 縣이 없습니다."),
    FOREIGN_COUNTY("현재 위치의 縣이 본인 세력의 소유가 아닙니다."),
    STATE_UNAVAILABLE("현재 縣의 소유·위치 상태를 확인할 수 없습니다."),
    WAREHOUSE_NOT_READY("縣 창고를 확인할 수 없습니다."),
    INSUFFICIENT_STOCK("縣 창고의 물자가 모자랍니다."),
    ALREADY_PROCESSED("이 순에는 이미 현장 행동을 실행했습니다."),
}

sealed interface HwihaFieldAssessment {
    data class Eligible(val county: DomesticCounty, val person: DomesticPerson) : HwihaFieldAssessment
    data class Rejected(val reason: HwihaFieldFailure) : HwihaFieldAssessment
}

/** Both admission and execution resolve the exact same county from spatial position, never general.cityId. */
object HwihaFieldRules {
    fun assess(request: HwihaFieldRequest, state: HwihaDomesticProjection): HwihaFieldAssessment {
        fun reject(reason: HwihaFieldFailure) = HwihaFieldAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return reject(HwihaFieldFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.inputId !in HwihaFieldInput.INPUT_IDS) return reject(HwihaFieldFailure.INVALID_INPUT)
        val person = state.person(request.actorId) ?: return reject(HwihaFieldFailure.ACTOR_NOT_FOUND)
        if (person.inBattle) return reject(HwihaFieldFailure.BATTLE_PENDING)
        val node = person.node ?: return reject(HwihaFieldFailure.POSITION_UNAVAILABLE)
        if (state.landProvinceIds?.contains(node) != true) return reject(HwihaFieldFailure.STATE_UNAVAILABLE)
        val counties = state.counties.filter { it.provinceId == node }
        if (counties.isEmpty()) return reject(HwihaFieldFailure.COUNTY_UNAVAILABLE)
        if (counties.size != 1) return reject(HwihaFieldFailure.STATE_UNAVAILABLE)
        val county = counties.single()
        if (person.nationId <= 0 || county.nationId != person.nationId) return reject(HwihaFieldFailure.FOREIGN_COUNTY)
        return HwihaFieldAssessment.Eligible(county, person)
    }

    /** A single affordability calculation is used by both the API snapshot and the execution world. */
    fun assessEconomy(inputId: String, person: DomesticPerson, countyId: Int,
        levels: HwihaCountyLevels?, stock: HwihaResources?, design: HwihaDomesticDesign,
        hometown: Boolean = false): HwihaFieldEconomyAssessment {
        if (levels == null) return HwihaFieldEconomyAssessment.Rejected(HwihaFieldFailure.STATE_UNAVAILABLE)
        val stats = HwihaSeatStats(person.leadership, person.strength, person.intelligence, person.politics,
            person.charm, hometown)
        val outcome = try { HwihaDomesticEffects.applyDirect(design, inputId, levels, stats) }
            catch (_: IllegalArgumentException) { return HwihaFieldEconomyAssessment.Rejected(HwihaFieldFailure.INVALID_INPUT) }
            catch (_: ArithmeticException) { return HwihaFieldEconomyAssessment.Rejected(HwihaFieldFailure.STATE_UNAVAILABLE) }
        if (outcome.debit != HwihaResources() || outcome.credit != HwihaResources()) {
            if (stock == null) return HwihaFieldEconomyAssessment.Rejected(HwihaFieldFailure.WAREHOUSE_NOT_READY)
            if (stock.debit(outcome.debit) == null)
                return HwihaFieldEconomyAssessment.Rejected(HwihaFieldFailure.INSUFFICIENT_STOCK)
        }
        return HwihaFieldEconomyAssessment.Eligible(countyId, outcome)
    }
}

sealed interface HwihaFieldEconomyAssessment {
    data class Eligible(val countyId: Int, val outcome: HwihaPolicyOutcome) : HwihaFieldEconomyAssessment
    data class Rejected(val reason: HwihaFieldFailure) : HwihaFieldEconomyAssessment
}
