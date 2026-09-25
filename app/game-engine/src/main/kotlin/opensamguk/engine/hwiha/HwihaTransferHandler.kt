package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*

/** One field-phase transfer between portable stocks. The actor always pays from personal stock. */
class HwihaTransferHandler(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val context: HwihaDomesticContext,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    fun handle(inputId: String, actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?,
        npcSelected: Boolean = false): HwihaTurnOutcome {
        fun reject(reason: HwihaTransferFailure) = HwihaTurnOutcome.Rejected(inputId, reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(HwihaTransferFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(HwihaTransferFailure.ACTOR_NOT_FOUND)
        val npc = npcSelected && ownerUserId == null && actor.npcState >= 2 && HwihaNpcDeploySelector.isUnowned(actor.userId)
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return HwihaTurnOutcome.Rejected(inputId, "FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        val request = HwihaTransferInput.parse(actorId, inputId, rawJson)
            ?: return reject(HwihaTransferFailure.INVALID_INPUT)
        if (catalog[inputId]?.deliveryState?.hasHandler != true)
            return HwihaTurnOutcome.Rejected(inputId, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val turnToken = actor.turnTime.toString()
        val previous = actor.meta[LAST_TURN_KEY] as? Map<*, *>
        if (previous?.get("turn") == turnToken) {
            if (previous["inputId"] == inputId && previous["requestId"] == requestId)
                return HwihaTurnOutcome.Applied(inputId,
                    (previous["effects"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return reject(HwihaTransferFailure.ALREADY_PROCESSED)
        }
        val assessment = HwihaTransferRules.assess(request, context.projection(world))
        if (assessment is HwihaTransferAssessment.Rejected) return reject(assessment.reason)
        val ready = assessment as HwihaTransferAssessment.Eligible
        val amount = with(HwihaTransferRules) { request.resource.amount(request.amount.toLong()) }
        val remaining = checkNotNull(ready.donorStock.debit(amount))
        val received = try { ready.receivedStock.credit(amount) }
            catch (_: ArithmeticException) { return reject(HwihaTransferFailure.STOCK_OVERFLOW) }
        val effects = listOf("resource:${request.resource.name}", "amount:-${request.amount}",
            if (inputId == HwihaTransferInput.GIFT) "recipientGeneralId:${ready.recipient!!.id}"
                else "recipientNationId:${ready.nation!!.id}")
        val recipient = ready.recipient
        if (recipient != null) {
            val target = world.getGeneralById(recipient.id) ?: return reject(HwihaTransferFailure.TARGET_UNAVAILABLE)
            val next = target.copy(gold = HwihaPortableStock.checkedColumn(received.money),
                rice = HwihaPortableStock.checkedColumn(received.grain),
                meta = HwihaPortableStock.withStock(target.meta, received))
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(target), PerTurnOverlay.toLogicGeneral(next))
            world.applyGeneralDirtyFree(next)
        } else {
            val nation = world.getNationById(ready.nation!!.id) ?: return reject(HwihaTransferFailure.NATION_UNAVAILABLE)
            val next = nation.copy(gold = HwihaPortableStock.checkedColumn(received.money),
                rice = HwihaPortableStock.checkedColumn(received.grain),
                meta = HwihaPortableStock.withStock(nation.meta, received))
            recorder.diffNation(PerTurnOverlay.toLogicNation(nation), PerTurnOverlay.toLogicNation(next))
            world.applyNationDirtyFree(next)
        }
        val stamp = mapOf("turn" to turnToken, "inputId" to inputId, "requestId" to requestId, "effects" to effects)
        val nextActor = actor.copy(gold = HwihaPortableStock.checkedColumn(remaining.money),
            rice = HwihaPortableStock.checkedColumn(remaining.grain),
            meta = HwihaPortableStock.withStock(actor.meta, remaining) + (LAST_TURN_KEY to stamp))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(actor), PerTurnOverlay.toLogicGeneral(nextActor))
        world.applyGeneralDirtyFree(nextActor)
        HwihaRecords.general(world, actorId, RecordKind.FIELD_APPLIED, "${actor.name}의 자원 이전을 마쳤습니다.",
            mapOf("inputId" to inputId, "requestId" to requestId))
        return HwihaTurnOutcome.Applied(inputId, effects)
    }
    companion object { private const val LAST_TURN_KEY = "hwihaTransferLastTurn" }
}
