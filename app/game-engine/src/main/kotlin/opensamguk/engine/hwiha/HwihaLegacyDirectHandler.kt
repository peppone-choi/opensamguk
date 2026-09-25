package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.*

/** Direct conversion, treasure/grain trade and one-hop warehouse transport. */
class HwihaLegacyDirectHandler(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val context: HwihaDomesticContext,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    fun handle(inputId: String, actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?,
        npcSelected: Boolean = false): HwihaTurnOutcome {
        fun reject(reason: HwihaLegacyDirectFailure) = HwihaTurnOutcome.Rejected(inputId, reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(HwihaLegacyDirectFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(HwihaLegacyDirectFailure.ACTOR_NOT_FOUND)
        val npc = npcSelected && ownerUserId == null && actor.npcState >= 2 && HwihaNpcDeploySelector.isUnowned(actor.userId)
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return HwihaTurnOutcome.Rejected(inputId, "FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        val request = HwihaLegacyDirectInput.parse(actorId, inputId, rawJson)
            ?: return reject(HwihaLegacyDirectFailure.INVALID_INPUT)
        if (catalog[inputId]?.deliveryState?.hasHandler != true)
            return HwihaTurnOutcome.Rejected(inputId, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val turnToken = actor.turnTime.toString()
        val previous = actor.meta[LAST_TURN_KEY] as? Map<*, *>
        if (previous?.get("turn") == turnToken) {
            if (previous["inputId"] == inputId && previous["requestId"] == requestId)
                return HwihaTurnOutcome.Applied(inputId, (previous["effects"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return reject(HwihaLegacyDirectFailure.ALREADY_PROCESSED)
        }
        val assessed = HwihaLegacyDirectRules.assess(request, context.projection(world))
        if (assessed is HwihaLegacyDirectAssessment.Rejected) return reject(assessed.reason)
        val ready = assessed as HwihaLegacyDirectAssessment.Eligible
        val design = HwihaLegacyDirectDesign.CANON
        val effects = mutableListOf<String>()
        var stock = ready.actorStock
        var inventory = ready.inventory
        when (request) {
            is HwihaLegacyDirectRequest.Convert -> {
                val unit = world.getBugokById(request.bugokId) ?: return reject(HwihaLegacyDirectFailure.BUGOK_UNAVAILABLE)
                world.updateBugok(unit.copy(crewTypeId = request.crewTypeId,
                    training = (unit.training - design.conversionTrainingLoss).coerceAtLeast(0)))
                effects += "bugokId:${unit.id}"
                effects += "crewTypeId:${request.crewTypeId}"
                effects += "training:${(unit.training - design.conversionTrainingLoss).coerceAtLeast(0) - unit.training}"
            }
            is HwihaLegacyDirectRequest.Equipment -> {
                val card = checkNotNull(ready.treasure)
                val warehouse = checkNotNull(ready.warehouse)
                val price = HwihaResources(money = card.purchaseCost.toLong())
                if (request.side == HwihaTradeSide.BUY) {
                    stock = checkNotNull(checkNotNull(stock).debit(price))
                    changeWarehouse(checkNotNull(ready.county).id, warehouse, warehouse.stock.credit(price))
                    inventory = inventory + card.header.id
                } else {
                    stock = checkNotNull(stock).credit(price)
                    changeWarehouse(checkNotNull(ready.county).id, warehouse, checkNotNull(warehouse.stock.debit(price)))
                    inventory = inventory - card.header.id
                }
                effects += "treasure:${card.header.id}"
                effects += "money:${if (request.side == HwihaTradeSide.BUY) -card.purchaseCost else card.purchaseCost}"
            }
            is HwihaLegacyDirectRequest.Grain -> {
                val warehouse = checkNotNull(ready.warehouse)
                val money = HwihaResources(money = design.grainTradeMoney.toLong())
                val grain = HwihaResources(grain = design.grainTradeGrain.toLong())
                if (request.side == HwihaTradeSide.BUY) {
                    stock = checkNotNull(checkNotNull(stock).debit(money)).credit(grain)
                    changeWarehouse(checkNotNull(ready.county).id, warehouse,
                        checkNotNull(warehouse.stock.debit(grain)).credit(money))
                } else {
                    stock = checkNotNull(checkNotNull(stock).debit(grain)).credit(money)
                    changeWarehouse(checkNotNull(ready.county).id, warehouse,
                        checkNotNull(warehouse.stock.debit(money)).credit(grain))
                }
                effects += "money:${if (request.side == HwihaTradeSide.BUY) -design.grainTradeMoney else design.grainTradeMoney}"
                effects += "grain:${if (request.side == HwihaTradeSide.BUY) design.grainTradeGrain else -design.grainTradeGrain}"
            }
            is HwihaLegacyDirectRequest.Transport -> {
                val from = checkNotNull(ready.warehouse)
                val to = checkNotNull(ready.targetWarehouse)
                val amount = with(HwihaLegacyDirectRules) { request.cargo.amount(request.amount.toLong()) }
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
        val current = world.getGeneralById(actorId) ?: return reject(HwihaLegacyDirectFailure.STATE_UNAVAILABLE)
        val nextStock = stock
        val meta = if (nextStock == null) current.meta else HwihaPortableStock.withStock(current.meta, nextStock)
        val next = current.copy(
            gold = nextStock?.let { HwihaPortableStock.checkedColumn(it.money) } ?: current.gold,
            rice = nextStock?.let { HwihaPortableStock.checkedColumn(it.grain) } ?: current.rice,
            meta = (if (request is HwihaLegacyDirectRequest.Equipment) HwihaTreasureInventory.withCards(meta, inventory) else meta) +
                (LAST_TURN_KEY to stamp))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(current), PerTurnOverlay.toLogicGeneral(next))
        world.applyGeneralDirtyFree(next)
        HwihaRecords.general(world, actorId, RecordKind.FIELD_APPLIED, "${actor.name}의 직접 행동을 마쳤습니다.",
            mapOf("inputId" to inputId, "requestId" to requestId))
        return HwihaTurnOutcome.Applied(inputId, effects)
    }

    private fun changeWarehouse(countyId: Int, current: HwihaCountyWarehouse, next: HwihaResources) {
        val county = checkNotNull(world.getCityById(countyId))
        world.updateCityMeta(recorder, countyId,
            county.meta + (HwihaCountyWarehouse.META_KEY to current.replace(next).toMetaValue()))
    }
    companion object { private const val LAST_TURN_KEY = "hwihaLegacyDirectLastTurn" }
}
