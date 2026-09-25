package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticProjection

import opensamguk.logic.economy.Resources

enum class HwihaTransferFailure(val message: String) {
    WRONG_RULE_PROFILE("이 월드에서는 자원 이전을 사용할 수 없습니다."),
    INVALID_INPUT("이전할 자원과 수량을 확인할 수 없습니다."),
    ACTOR_NOT_FOUND("행동할 장수를 찾을 수 없습니다."),
    BATTLE_PENDING("조우 처리가 끝나야 자원을 이전할 수 있습니다."),
    POSITION_UNAVAILABLE("장수의 현재 육상 위치를 확인할 수 없습니다."),
    COUNTY_UNAVAILABLE("현재 위치에 행정 縣이 없습니다."),
    TARGET_UNAVAILABLE("현재 省에서 만날 수 있는 다른 장수가 아닙니다."),
    NATION_UNAVAILABLE("현재 縣에 국고를 가진 세력이 없습니다."),
    STATE_UNAVAILABLE("보유 자원 상태를 확인할 수 없습니다."),
    INSUFFICIENT_STOCK("보유한 자원이 부족합니다."),
    STOCK_OVERFLOW("받는 쪽의 자원 보유 한도를 넘습니다."),
    ALREADY_PROCESSED("이 순에는 이미 자원 이전을 실행했습니다."),
}

sealed interface HwihaTransferAssessment {
    data class Eligible(val actor: DomesticPerson, val recipient: DomesticPerson? = null,
        val nation: DomesticNation? = null, val county: DomesticCounty? = null,
        val donorStock: Resources, val receivedStock: Resources) : HwihaTransferAssessment
    data class Rejected(val reason: HwihaTransferFailure) : HwihaTransferAssessment
}

object HwihaTransferRules {
    fun assess(request: HwihaTransferRequest, state: DomesticProjection): HwihaTransferAssessment {
        fun reject(reason: HwihaTransferFailure) = HwihaTransferAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return reject(HwihaTransferFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.inputId !in HwihaTransferInput.INPUT_IDS || request.amount <= 0)
            return reject(HwihaTransferFailure.INVALID_INPUT)
        if (request.resource !in setOf(HwihaTransferResource.MONEY, HwihaTransferResource.GRAIN))
            return reject(HwihaTransferFailure.INVALID_INPUT)
        val actor = state.person(request.actorId) ?: return reject(HwihaTransferFailure.ACTOR_NOT_FOUND)
        if (actor.inBattle) return reject(HwihaTransferFailure.BATTLE_PENDING)
        val node = actor.node ?: return reject(HwihaTransferFailure.POSITION_UNAVAILABLE)
        if (state.landProvinceIds?.contains(node) != true) return reject(HwihaTransferFailure.POSITION_UNAVAILABLE)
        val donorStock = try { HwihaPortableStock.read(actor.meta, actor.gold, actor.rice) }
            catch (_: IllegalArgumentException) { return reject(HwihaTransferFailure.STATE_UNAVAILABLE) }
        val debit = request.resource.amount(request.amount.toLong())
        val remaining = donorStock.debit(debit) ?: return reject(HwihaTransferFailure.INSUFFICIENT_STOCK)
        val county: DomesticCounty?
        val recipient: DomesticPerson?
        val nation: DomesticNation?
        if (request.inputId == HwihaTransferInput.GIFT) {
            county = null
            nation = null
            recipient = request.targetGeneralId?.let(state::person)
                ?.takeIf { it.id != actor.id && it.node == node && !it.inBattle }
                ?: return reject(HwihaTransferFailure.TARGET_UNAVAILABLE)
        } else {
            if (request.targetGeneralId != null) return reject(HwihaTransferFailure.INVALID_INPUT)
            recipient = null
            val local = state.counties.filter { it.provinceId == node }
            if (local.size > 1) return reject(HwihaTransferFailure.STATE_UNAVAILABLE)
            county = local.singleOrNull() ?: return reject(HwihaTransferFailure.COUNTY_UNAVAILABLE)
            nation = state.nation(county.nationId) ?: return reject(HwihaTransferFailure.NATION_UNAVAILABLE)
        }
        val received = try {
            if (recipient != null) HwihaPortableStock.read(recipient.meta, recipient.gold, recipient.rice)
            else HwihaPortableStock.read(nation!!.meta, nation.gold, nation.rice)
        } catch (_: IllegalArgumentException) { return reject(HwihaTransferFailure.STATE_UNAVAILABLE) }
        val next = try { received.credit(debit) }
            catch (_: ArithmeticException) { return reject(HwihaTransferFailure.STOCK_OVERFLOW) }
        if (remaining.money > Int.MAX_VALUE || remaining.grain > Int.MAX_VALUE ||
            next.money > Int.MAX_VALUE || next.grain > Int.MAX_VALUE)
            return reject(HwihaTransferFailure.STOCK_OVERFLOW)
        return HwihaTransferAssessment.Eligible(actor, recipient, nation, county, donorStock, received)
    }

    fun HwihaTransferResource.amount(value: Long): Resources = when (this) {
        HwihaTransferResource.MONEY -> Resources(money = value)
        HwihaTransferResource.GRAIN -> Resources(grain = value)
        HwihaTransferResource.IRON -> Resources(iron = value)
        HwihaTransferResource.TIMBER -> Resources(timber = value)
        HwihaTransferResource.HORSES -> Resources(horses = value)
    }
}
