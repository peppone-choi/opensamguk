package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticProjection

import opensamguk.logic.economy.Resources

enum class TransferFailure(val message: String) {
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

sealed interface TransferAssessment {
    data class Eligible(val actor: DomesticPerson, val recipient: DomesticPerson? = null,
        val nation: DomesticNation? = null, val county: DomesticCounty? = null,
        val donorStock: Resources, val receivedStock: Resources) : TransferAssessment
    data class Rejected(val reason: TransferFailure) : TransferAssessment
}

object TransferRules {
    fun assess(request: TransferRequest, state: DomesticProjection): TransferAssessment {
        fun reject(reason: TransferFailure) = TransferAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return reject(TransferFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || request.inputId !in TransferInput.INPUT_IDS || request.amount <= 0)
            return reject(TransferFailure.INVALID_INPUT)
        if (request.resource !in setOf(TransferResource.MONEY, TransferResource.GRAIN))
            return reject(TransferFailure.INVALID_INPUT)
        val actor = state.person(request.actorId) ?: return reject(TransferFailure.ACTOR_NOT_FOUND)
        if (actor.inBattle) return reject(TransferFailure.BATTLE_PENDING)
        val node = actor.node ?: return reject(TransferFailure.POSITION_UNAVAILABLE)
        if (state.landProvinceIds?.contains(node) != true) return reject(TransferFailure.POSITION_UNAVAILABLE)
        val donorStock = try { PortableStock.read(actor.meta, actor.gold, actor.rice) }
            catch (_: IllegalArgumentException) { return reject(TransferFailure.STATE_UNAVAILABLE) }
        val debit = request.resource.amount(request.amount.toLong())
        val remaining = donorStock.debit(debit) ?: return reject(TransferFailure.INSUFFICIENT_STOCK)
        val county: DomesticCounty?
        val recipient: DomesticPerson?
        val nation: DomesticNation?
        if (request.inputId == TransferInput.GIFT) {
            county = null
            nation = null
            recipient = request.targetGeneralId?.let(state::person)
                ?.takeIf { it.id != actor.id && it.node == node && !it.inBattle }
                ?: return reject(TransferFailure.TARGET_UNAVAILABLE)
        } else {
            if (request.targetGeneralId != null) return reject(TransferFailure.INVALID_INPUT)
            recipient = null
            val local = state.counties.filter { it.provinceId == node }
            if (local.size > 1) return reject(TransferFailure.STATE_UNAVAILABLE)
            county = local.singleOrNull() ?: return reject(TransferFailure.COUNTY_UNAVAILABLE)
            nation = state.nation(county.nationId) ?: return reject(TransferFailure.NATION_UNAVAILABLE)
        }
        val received = try {
            if (recipient != null) PortableStock.read(recipient.meta, recipient.gold, recipient.rice)
            else PortableStock.read(nation!!.meta, nation.gold, nation.rice)
        } catch (_: IllegalArgumentException) { return reject(TransferFailure.STATE_UNAVAILABLE) }
        val next = try { received.credit(debit) }
            catch (_: ArithmeticException) { return reject(TransferFailure.STOCK_OVERFLOW) }
        if (remaining.money > Int.MAX_VALUE || remaining.grain > Int.MAX_VALUE ||
            next.money > Int.MAX_VALUE || next.grain > Int.MAX_VALUE)
            return reject(TransferFailure.STOCK_OVERFLOW)
        return TransferAssessment.Eligible(actor, recipient, nation, county, donorStock, received)
    }

    fun TransferResource.amount(value: Long): Resources = when (this) {
        TransferResource.MONEY -> Resources(money = value)
        TransferResource.GRAIN -> Resources(grain = value)
        TransferResource.IRON -> Resources(iron = value)
        TransferResource.TIMBER -> Resources(timber = value)
        TransferResource.HORSES -> Resources(horses = value)
    }
}
