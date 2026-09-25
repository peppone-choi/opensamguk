package opensamguk.logic.vassal

import opensamguk.logic.economy.Resources

data class VassalWarehouse(val countyId: Int, val componentId: String, val stock: Resources) {
    init { require(countyId > 0 && componentId.isNotBlank()) }
}

data class TributeTransfer(val fromCountyId: Int, val toCountyId: Int, val amount: Resources)

data class TributeSettlement(
    val due: Resources,
    val paid: Resources,
    val unpaid: Resources,
    val transfers: List<TributeTransfer>,
    val nextStockByCounty: Map<Int, Resources>,
) {
    val fulfilled: Boolean get() = unpaid == Resources()
}

object VassalTribute {
    /** Apply after monthly county income. A disconnected fief fragment cannot pay into a remote ruler warehouse. */
    fun settle(
        contract: VassalContract,
        incomeByFiefCounty: Map<Int, Resources>,
        warehouses: Collection<VassalWarehouse>,
        rulerWarehouseCountyIds: Set<Int>,
        atTurn: Long,
    ): TributeSettlement {
        require(contract.activeAt(atTurn) && incomeByFiefCounty.keys == contract.fiefCountyIds)
        require(warehouses.map { it.countyId }.distinct().size == warehouses.size)
        val byCounty = warehouses.associateBy { it.countyId }
        require((contract.fiefCountyIds + rulerWarehouseCountyIds).all { it in byCounty })
        require(contract.fiefCountyIds.intersect(rulerWarehouseCountyIds).isEmpty())
        val next = warehouses.sortedBy { it.countyId }.associate { it.countyId to it.stock }.toMutableMap()
        val destinations = rulerWarehouseCountyIds.sorted().map { byCounty.getValue(it) }.groupBy { it.componentId }
        var due = Resources()
        var paid = Resources()
        val transfers = mutableListOf<TributeTransfer>()
        contract.fiefCountyIds.sorted().forEach { countyId ->
            val amount = proportional(incomeByFiefCounty.getValue(countyId), contract.tributePercent)
            due = due.credit(amount)
            val source = byCounty.getValue(countyId)
            val destination = destinations[source.componentId]?.firstOrNull() ?: return@forEach
            val available = next.getValue(countyId)
            val transfer = minimum(amount, available)
            if (transfer == Resources()) return@forEach
            next[countyId] = requireNotNull(available.debit(transfer))
            next[destination.countyId] = next.getValue(destination.countyId).credit(transfer)
            paid = paid.credit(transfer)
            transfers += TributeTransfer(countyId, destination.countyId, transfer)
        }
        return TributeSettlement(due, paid, requireNotNull(due.debit(paid)), transfers, next.toSortedMap())
    }

    private fun proportional(resources: Resources, rate: Int) = Resources(
        part(resources.money, rate), part(resources.grain, rate), part(resources.iron, rate),
        part(resources.timber, rate), part(resources.horses, rate))

    private fun part(value: Long, rate: Int): Long {
        require(rate in 0..100)
        return (value / 100) * rate + (value % 100) * rate / 100
    }

    private fun minimum(a: Resources, b: Resources) = Resources(
        minOf(a.money, b.money), minOf(a.grain, b.grain), minOf(a.iron, b.iron),
        minOf(a.timber, b.timber), minOf(a.horses, b.horses))
}

enum class ReinforcementReply { ACCEPT, DELAY, REDUCE, REFUSE }
enum class ReinforcementOutcome { PENDING, ACCEPTED, DELAYED, REDUCED, BREACH }

data class ReinforcementRequest(val contractId: String, val operationId: String, val requestedTroops: Int, val issuedTurn: Long) {
    init { require(contractId.isNotBlank() && operationId.isNotBlank() && requestedTroops > 0 && issuedTurn >= 0) }
}

data class ReinforcementResponse(val kind: ReinforcementReply, val offeredTroops: Int, val answeredTurn: Long) {
    init { require(offeredTroops >= 0 && answeredTurn >= 0) }
}

data class ReinforcementDecision(val outcome: ReinforcementOutcome, val committedTroops: Int, val dueTurn: Long)

object VassalReinforcement {
    fun assess(
        contract: VassalContract,
        request: ReinforcementRequest,
        response: ReinforcementResponse?,
        nowTurn: Long,
        rules: VassalRules,
    ): ReinforcementDecision {
        require(contract.activeAt(request.issuedTurn) && request.contractId == contract.id && nowTurn >= request.issuedTurn)
        val deadline = Math.addExact(request.issuedTurn, rules.reinforcementReplyTurns.toLong())
        if (response == null) return ReinforcementDecision(
            if (nowTurn > deadline) ReinforcementOutcome.BREACH else ReinforcementOutcome.PENDING, 0, deadline)
        require(response.answeredTurn in request.issuedTurn..nowTurn)
        if (response.answeredTurn > deadline) return ReinforcementDecision(ReinforcementOutcome.BREACH, 0, deadline)
        val obligated = minOf(request.requestedTroops, contract.reinforcementTroops)
        return when (response.kind) {
            ReinforcementReply.ACCEPT -> {
                require(response.offeredTroops >= obligated)
                ReinforcementDecision(ReinforcementOutcome.ACCEPTED, response.offeredTroops, response.answeredTurn)
            }
            ReinforcementReply.DELAY -> {
                require(response.offeredTroops >= obligated)
                ReinforcementDecision(ReinforcementOutcome.DELAYED, response.offeredTroops, deadline)
            }
            ReinforcementReply.REDUCE -> {
                val minimum = obligated.toLong() * rules.minimumReducedPercent
                if (response.offeredTroops.toLong() * 100 < minimum) ReinforcementDecision(ReinforcementOutcome.BREACH, 0, deadline)
                else ReinforcementDecision(ReinforcementOutcome.REDUCED, response.offeredTroops, deadline)
            }
            ReinforcementReply.REFUSE -> ReinforcementDecision(ReinforcementOutcome.BREACH, 0, deadline)
        }
    }
}
