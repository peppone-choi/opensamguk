package opensamguk.engine.campaign

import opensamguk.engine.turn.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*

/** Direct conversion, treasure/grain trade and one-hop warehouse transport. */
class LegacyDirectHandler(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val context: DomesticContext,
    private val catalog: InputCatalog = InputCatalog.load()) {
    fun handle(inputId: String, actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?,
        npcSelected: Boolean = false): TurnOutcome {
        fun reject(reason: DirectFailure) = TurnOutcome.Rejected(inputId, reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(DirectFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(DirectFailure.ACTOR_NOT_FOUND)
        val npc = npcSelected && ownerUserId == null && actor.npcState >= 2 && NpcDeploySelector.isUnowned(actor.userId)
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return TurnOutcome.Rejected(inputId, "FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        val request = DirectInput.parse(actorId, inputId, rawJson)
            ?: return reject(DirectFailure.INVALID_INPUT)
        if (catalog[inputId]?.deliveryState?.hasHandler != true)
            return TurnOutcome.Rejected(inputId, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val turnToken = actor.turnTime.toString()
        val previous = actor.meta[LAST_TURN_KEY] as? Map<*, *>
        if (previous?.get("turn") == turnToken) {
            if (previous["inputId"] == inputId && previous["requestId"] == requestId)
                return TurnOutcome.Applied(inputId, (previous["effects"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return reject(DirectFailure.ALREADY_PROCESSED)
        }
        val assessed = DirectRules.assess(request, context.projection(world))
        if (assessed is DirectAssessment.Rejected) return reject(assessed.reason)
        val ready = assessed as DirectAssessment.Eligible
        val design = DirectDesign.CANON
        val effects = mutableListOf<String>()
        var stock = ready.actorStock
        var inventory = ready.inventory
        when (request) {
            is DirectRequest.Convert -> {
                val unit = world.getBugokById(request.bugokId) ?: return reject(DirectFailure.BUGOK_UNAVAILABLE)
                world.updateBugok(unit.copy(crewTypeId = request.crewTypeId,
                    training = (unit.training - design.conversionTrainingLoss).coerceAtLeast(0)))
                effects += "bugokId:${unit.id}"
                effects += "crewTypeId:${request.crewTypeId}"
                effects += "training:${(unit.training - design.conversionTrainingLoss).coerceAtLeast(0) - unit.training}"
            }
            is DirectRequest.Equipment -> {
                val card = checkNotNull(ready.treasure)
                val warehouse = checkNotNull(ready.warehouse)
                val price = Resources(money = card.purchaseCost.toLong())
                if (request.side == TradeSide.BUY) {
                    stock = checkNotNull(checkNotNull(stock).debit(price))
                    changeWarehouse(checkNotNull(ready.county).id, warehouse, warehouse.stock.credit(price))
                    inventory = inventory + card.header.id
                } else {
                    stock = checkNotNull(stock).credit(price)
                    changeWarehouse(checkNotNull(ready.county).id, warehouse, checkNotNull(warehouse.stock.debit(price)))
                    inventory = inventory - card.header.id
                }
                effects += "treasure:${card.header.id}"
                effects += "money:${if (request.side == TradeSide.BUY) -card.purchaseCost else card.purchaseCost}"
            }
            is DirectRequest.Grain -> {
                val warehouse = checkNotNull(ready.warehouse)
                val money = Resources(money = design.grainTradeMoney.toLong())
                val grain = Resources(grain = design.grainTradeGrain.toLong())
                if (request.side == TradeSide.BUY) {
                    stock = checkNotNull(checkNotNull(stock).debit(money)).credit(grain)
                    changeWarehouse(checkNotNull(ready.county).id, warehouse,
                        checkNotNull(warehouse.stock.debit(grain)).credit(money))
                } else {
                    stock = checkNotNull(checkNotNull(stock).debit(grain)).credit(money)
                    changeWarehouse(checkNotNull(ready.county).id, warehouse,
                        checkNotNull(warehouse.stock.debit(money)).credit(grain))
                }
                effects += "money:${if (request.side == TradeSide.BUY) -design.grainTradeMoney else design.grainTradeMoney}"
                effects += "grain:${if (request.side == TradeSide.BUY) design.grainTradeGrain else -design.grainTradeGrain}"
            }
            is DirectRequest.Transport -> {
                val from = checkNotNull(ready.warehouse)
                val to = checkNotNull(ready.targetWarehouse)
                val amount = with(DirectRules) { request.cargo.amount(request.amount.toLong()) }
                val nextFrom = checkNotNull(from.stock.debit(amount))
                val nextTo = to.stock.credit(amount)
                changeWarehouse(checkNotNull(ready.county).id, from, nextFrom)
                changeWarehouse(checkNotNull(ready.targetCounty).id, to, nextTo)
                effects += "targetCountyId:${request.targetCountyId}"
                effects += "cargo:${request.cargo.name}"
                effects += "amount:${request.amount}"
            }
        }
        val stamp = mapOf("turn" to turnToken, "inputId" to inputId, "requestId" to requestId, "effects" to effects)
        val current = world.getGeneralById(actorId) ?: return reject(DirectFailure.STATE_UNAVAILABLE)
        val nextStock = stock
        val meta = if (nextStock == null) current.meta else PortableStock.withStock(current.meta, nextStock)
        val next = current.copy(
            gold = nextStock?.let { PortableStock.checkedColumn(it.money) } ?: current.gold,
            rice = nextStock?.let { PortableStock.checkedColumn(it.grain) } ?: current.rice,
            meta = (if (request is DirectRequest.Equipment) TreasureInventory.withCards(meta, inventory) else meta) +
                (LAST_TURN_KEY to stamp))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(current), PerTurnOverlay.toLogicGeneral(next))
        world.applyGeneralDirtyFree(next)
        Records.general(world, actorId, RecordKind.FIELD_APPLIED, "${actor.name}의 직접 행동을 마쳤습니다.",
            mapOf("inputId" to inputId, "requestId" to requestId))
        return TurnOutcome.Applied(inputId, effects)
    }

    private fun changeWarehouse(countyId: Int, current: CountyWarehouse, next: Resources) {
        val county = checkNotNull(world.getCityById(countyId))
        world.updateCityMeta(recorder, countyId,
            county.meta + (CountyWarehouse.META_KEY to current.replace(next).toMetaValue()))
    }
    companion object { private const val LAST_TURN_KEY = "hwihaLegacyDirectLastTurn" }
}
