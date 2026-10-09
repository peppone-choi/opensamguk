package opensamguk.gameapi.court.reward

import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.WarehouseFundingScope
import opensamguk.logic.input.RewardInput
import opensamguk.logic.input.RewardMoneyLimit
import opensamguk.logic.war.CampaignBalance

/** Estimates persisted state only. Queue admission, execution and history checks remain authoritative elsewhere. */
class RewardOptionsProjection {
    private data class Funding(val dto: RewardFundingDto, val warehouses: List<CountyWarehouse> = emptyList(),
        val capital: Int? = null)

    internal fun project(read: RewardReadSnapshot, retainerId: Int?, money: String?): RewardOptionsDto {
        if (read.status != "READY") return RewardOptionsDto.unavailable(read.generalId,
            read.reason ?: "WORLD_UNAVAILABLE", read.status, read.date)
        val funding = read.cards.associate { it.id to funding(read, read.people[it.recipientId]) }
        val cards = read.cards.map { card ->
            val recipient = read.people[card.recipientId]
            RewardCardDto(card.id, card.recipientId, recipient?.name, card.loyalty, LOYALTY_CAP - card.loyalty,
                RewardMoneyLimit.maximumFor(card.loyalty), recipient?.cityId, funding.getValue(card.id).dto)
        }
        return RewardOptionsDto("READY", null, read.generalId, read.date, RewardRuleDto(
            CampaignBalance.REWARD_MONEY_PER_LOYALTY, CampaignBalance.REWARD_MAX_LOYALTY_GAIN,
            CampaignBalance.REWARD_MONEY_PER_LOYALTY, RewardInput.MAX_MONEY, LOYALTY_CAP), read.queued, cards,
            retainerId?.let { preview(read, it, money, funding[it]) })
    }

    private fun funding(read: RewardReadSnapshot, person: RewardPerson?): Funding {
        if (person == null) return unavailable("RECIPIENT_MISSING")
        if (read.payerNationId <= 0) return none("PAYER_LANDLESS")
        val location = read.cities.singleOrNull { it.id == person.cityId } ?: return unavailable("LOCATION_UNKNOWN")
        if (location.nationId == 0) return none("LOCATION_NEUTRAL")
        if (location.nationId != read.payerNationId) return none("LOCATION_FOREIGN")
        val scope = if (location.supplied) "NETWORK" else "ISOLATED"
        if (!read.payerNationPresent) return unavailable("PAYER_NATION_MISSING", scope)
        val byId = read.cities.associateBy { it.id }
        val warehouses = linkedMapOf<Int, CountyWarehouse>()
        val ids = try {
            WarehouseFundingScope.countiesFor(read.payerNationId, person.cityId, byId::get,
                { read.capitalCityId }, { read.cities }, { it.id }, { it.nationId }, { it.supplied }) { id ->
                if (id !in read.countyIds) false else CountyWarehouse.read(byId.getValue(id).meta, id)
                    ?.also { warehouses[id] = it } != null
            }
        } catch (_: IllegalArgumentException) { return unavailable("WAREHOUSE_MALFORMED", scope) }
        val ordered = ids.map(warehouses::getValue)
        val total = try { ordered.fold(0L) { sum, warehouse -> Math.addExact(sum, warehouse.stock.money) } }
            catch (_: ArithmeticException) { return unavailable("TOTAL_OVERFLOW", scope) }
        return Funding(RewardFundingDto("KNOWN", scope, null, null, total.toString(), ordered.size), ordered,
            read.capitalCityId)
    }

    private fun none(reason: String) = Funding(RewardFundingDto("KNOWN", "NONE", reason, null, "0", 0))
    private fun unavailable(reason: String, scope: String = "NONE") =
        Funding(RewardFundingDto("UNAVAILABLE", scope, null, reason, null, null))

    private fun preview(read: RewardReadSnapshot, retainerId: Int, raw: String?, funding: Funding?): RewardPreviewDto {
        val amount = raw?.takeIf { Regex("[1-9][0-9]{0,9}").matches(it) }?.toLongOrNull()
            ?.takeIf { it <= RewardInput.MAX_MONEY }
        val card = read.cards.singleOrNull { it.id == retainerId }
        var gain: Int? = null
        var after: Int? = null
        var waste: Long? = null
        var debit: List<RewardDebitDto>? = null
        val verdict = when {
            card == null || read.people[card.recipientId] == null -> "CARD_UNAVAILABLE"
            raw == null -> "NO_AMOUNT"
            amount == null -> "INVALID_AMOUNT"
            amount < CampaignBalance.REWARD_MONEY_PER_LOYALTY -> "TOO_SMALL"
            amount > RewardMoneyLimit.maximumFor(card.loyalty) -> "REWARD_OVER_CAP"
            funding?.dto?.status != "KNOWN" -> "FUNDING_UNAVAILABLE"
            amount > funding.dto.usableMoney!!.toLong() -> "INSUFFICIENT_STOCK"
            else -> "COVERED_AT_SNAPSHOT"
        }
        if (card != null && amount != null && verdict in setOf("TOO_SMALL", "FUNDING_UNAVAILABLE",
                "INSUFFICIENT_STOCK", "COVERED_AT_SNAPSHOT")) {
            gain = minOf(amount / CampaignBalance.REWARD_MONEY_PER_LOYALTY,
                CampaignBalance.REWARD_MAX_LOYALTY_GAIN.toLong(), (LOYALTY_CAP - card.loyalty).toLong()).toInt()
            after = card.loyalty + gain
            waste = amount - gain * CampaignBalance.REWARD_MONEY_PER_LOYALTY
        }
        if (verdict == "COVERED_AT_SNAPSHOT") {
            var remaining = amount!!
            debit = funding!!.warehouses.mapNotNull { warehouse ->
                val take = minOf(remaining, warehouse.stock.money)
                remaining -= take
                if (take == 0L) null else RewardDebitDto(warehouse.countyId, warehouse.countyId == funding.capital,
                    take.toString(), warehouse.stock.money.toString(), warehouse.revision.toString())
            }
        }
        return RewardPreviewDto(retainerId, amount, verdict, gain, after, waste,
            funding?.dto?.usableMoney, debit, NOT_CHECKED)
    }

    private companion object {
        // Existing RewardExecutor clamps at 100; RewardMoneyLimit uses the same cap.
        const val LOYALTY_CAP = 100
        val NOT_CHECKED = listOf("QUEUE_ADMISSION", "REWARD_HISTORY", "CONCURRENT_DEBITS", "STATE_AFTER_SNAPSHOT")
    }
}
