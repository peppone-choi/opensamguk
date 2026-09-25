package opensamguk.engine.campaign

import opensamguk.engine.turn.*
import opensamguk.logic.input.*

/** One field-phase transfer between portable stocks. The actor always pays from personal stock. */
class TransferHandler(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val context: DomesticContext,
    private val catalog: InputCatalog = InputCatalog.load()) {
    fun handle(inputId: String, actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?,
        npcSelected: Boolean = false): TurnOutcome {
        fun reject(reason: TransferFailure) = TurnOutcome.Rejected(inputId, reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(TransferFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(TransferFailure.ACTOR_NOT_FOUND)
        val npc = npcSelected && ownerUserId == null && actor.npcState >= 2 && NpcDeploySelector.isUnowned(actor.userId)
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return TurnOutcome.Rejected(inputId, "FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        val request = TransferInput.parse(actorId, inputId, rawJson)
            ?: return reject(TransferFailure.INVALID_INPUT)
        if (catalog[inputId]?.deliveryState?.hasHandler != true)
            return TurnOutcome.Rejected(inputId, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val turnToken = actor.turnTime.toString()
        val previous = actor.meta[LAST_TURN_KEY] as? Map<*, *>
        if (previous?.get("turn") == turnToken) {
            if (previous["inputId"] == inputId && previous["requestId"] == requestId)
                return TurnOutcome.Applied(inputId,
                    (previous["effects"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return reject(TransferFailure.ALREADY_PROCESSED)
        }
        val assessment = TransferRules.assess(request, context.projection(world))
        if (assessment is TransferAssessment.Rejected) return reject(assessment.reason)
        val ready = assessment as TransferAssessment.Eligible
        val amount = with(TransferRules) { request.resource.amount(request.amount.toLong()) }
        val remaining = checkNotNull(ready.donorStock.debit(amount))
        val received = try { ready.receivedStock.credit(amount) }
            catch (_: ArithmeticException) { return reject(TransferFailure.STOCK_OVERFLOW) }
        val effects = listOf("resource:${request.resource.name}", "amount:-${request.amount}",
            if (inputId == TransferInput.GIFT) "recipientGeneralId:${ready.recipient!!.id}"
                else "recipientNationId:${ready.nation!!.id}")
        val recipient = ready.recipient
        if (recipient != null) {
            val target = world.getGeneralById(recipient.id) ?: return reject(TransferFailure.TARGET_UNAVAILABLE)
            val next = target.copy(gold = PortableStock.checkedColumn(received.money),
                rice = PortableStock.checkedColumn(received.grain),
                meta = PortableStock.withStock(target.meta, received))
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(target), PerTurnOverlay.toLogicGeneral(next))
            world.applyGeneralDirtyFree(next)
        } else {
            val nation = world.getNationById(ready.nation!!.id) ?: return reject(TransferFailure.NATION_UNAVAILABLE)
            val next = nation.copy(gold = PortableStock.checkedColumn(received.money),
                rice = PortableStock.checkedColumn(received.grain),
                meta = PortableStock.withStock(nation.meta, received))
            recorder.diffNation(PerTurnOverlay.toLogicNation(nation), PerTurnOverlay.toLogicNation(next))
            world.applyNationDirtyFree(next)
        }
        val stamp = mapOf("turn" to turnToken, "inputId" to inputId, "requestId" to requestId, "effects" to effects)
        val nextActor = actor.copy(gold = PortableStock.checkedColumn(remaining.money),
            rice = PortableStock.checkedColumn(remaining.grain),
            meta = PortableStock.withStock(actor.meta, remaining) + (LAST_TURN_KEY to stamp))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(actor), PerTurnOverlay.toLogicGeneral(nextActor))
        world.applyGeneralDirtyFree(nextActor)
        Records.general(world, actorId, RecordKind.FIELD_APPLIED, "${actor.name}의 자원 이전을 마쳤습니다.",
            mapOf("inputId" to inputId, "requestId" to requestId))
        return TurnOutcome.Applied(inputId, effects)
    }
    companion object { private const val LAST_TURN_KEY = "hwihaTransferLastTurn" }
}
