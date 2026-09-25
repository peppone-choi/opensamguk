package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.input.*

/** Political-phase retirement transfers the personal retinue to the named direct retainer. */
class HwihaRetireHandler(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val context: HwihaDomesticContext,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    fun handle(actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?, npcSelected: Boolean = false): HwihaTurnOutcome {
        fun reject(reason: HwihaRetireFailure) = HwihaTurnOutcome.Rejected(HwihaRetireInput.INPUT_ID, reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(HwihaRetireFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(HwihaRetireFailure.ACTOR_NOT_FOUND)
        val request = HwihaRetireInput.parse(actorId, rawJson) ?: return reject(HwihaRetireFailure.INVALID_INPUT)
        val turnToken = actor.turnTime.toString()
        val previous = actor.meta[LAST_TURN_KEY] as? Map<*, *>
        if (previous?.get("turn") == turnToken) {
            if (previous["requestId"] == requestId && previous["ownerUserId"] == ownerUserId &&
                previous["successorGeneralId"] == request.successorGeneralId)
                return HwihaTurnOutcome.Applied(HwihaRetireInput.INPUT_ID,
                    (previous["effects"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return reject(HwihaRetireFailure.ALREADY_PROCESSED)
        }
        val npc = npcSelected && ownerUserId == null && actor.npcState >= 2 && HwihaNpcDeploySelector.isUnowned(actor.userId)
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return HwihaTurnOutcome.Rejected(HwihaRetireInput.INPUT_ID, "FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        if (catalog[HwihaRetireInput.INPUT_ID]?.deliveryState?.hasHandler != true)
            return HwihaTurnOutcome.Rejected(HwihaRetireInput.INPUT_ID,
                InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val assessed = HwihaRetireRules.assess(request, context.projection(world))
        if (assessed is HwihaRetireAssessment.Rejected) return reject(assessed.reason)
        val ready = assessed as HwihaRetireAssessment.Eligible
        val successor = world.getGeneralById(ready.successor.id) ?: return reject(HwihaRetireFailure.SUCCESSOR_UNAVAILABLE)
        val cards = world.listRetainers().filter { it.masterGeneralId == actorId }
        val outerCards = world.listRetainers().filter { it.generalId == actorId }
        if (outerCards.size > 1 || outerCards.any { it.masterGeneralId == request.successorGeneralId })
            return reject(HwihaRetireFailure.STATE_UNAVAILABLE)
        val successorCard = cards.singleOrNull { it.id == ready.successorCard.id }
            ?: return reject(HwihaRetireFailure.SUCCESSOR_NOT_RETAINER)
        if (cards.any { it.generalId != null && world.getGeneralById(it.generalId)?.nationId != actor.nationId })
            return reject(HwihaRetireFailure.STATE_UNAVAILABLE)
        val transferred = try {
            val from = HwihaPortableStock.read(actor.meta, actor.gold, actor.rice)
            val to = HwihaPortableStock.read(successor.meta, successor.gold, successor.rice)
            to.credit(from).also {
                HwihaPortableStock.checkedColumn(it.money)
                HwihaPortableStock.checkedColumn(it.grain)
            }
        } catch (_: IllegalArgumentException) { return reject(HwihaRetireFailure.STATE_UNAVAILABLE) }
          catch (_: ArithmeticException) { return reject(HwihaRetireFailure.STATE_UNAVAILABLE) }
        val nation = if (ready.wasLord) world.getNationById(actor.nationId)
            ?: return reject(HwihaRetireFailure.STATE_UNAVAILABLE) else null
        if (nation != null && nation.chiefGeneralId != null && nation.chiefGeneralId != actorId)
            return reject(HwihaRetireFailure.STATE_UNAVAILABLE)
        val effects = listOf("successorGeneralId:${successor.id}", "retainers:${cards.size - 1}",
            "bugoks:${world.listBugoks().count { it.masterGeneralId == actorId }}")
        val stamp = mapOf("turn" to turnToken, "requestId" to requestId, "ownerUserId" to ownerUserId,
            "successorGeneralId" to request.successorGeneralId, "effects" to effects)
        val retired = actor.copy(userId = null, npcState = 5, officerLevel = 1, gold = 0, rice = 0,
            meta = HwihaPortableStock.withStock(actor.meta, opensamguk.logic.economy.Resources()) +
                (HwihaLordStatus.META_KEY to false) + ("hwihaRetired" to true) + (LAST_TURN_KEY to stamp))
        val inherited = successor.copy(userId = actor.userId ?: successor.userId,
            npcState = if (actor.userId != null) 0 else successor.npcState,
            officerLevel = if (ready.wasLord) 12 else successor.officerLevel,
            gold = HwihaPortableStock.checkedColumn(transferred.money),
            rice = HwihaPortableStock.checkedColumn(transferred.grain),
            meta = HwihaPortableStock.withStock(successor.meta, transferred) +
                (HwihaLordStatus.META_KEY to ready.wasLord))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(actor), PerTurnOverlay.toLogicGeneral(retired))
        world.applyGeneralDirtyFree(retired)
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(successor), PerTurnOverlay.toLogicGeneral(inherited))
        world.applyGeneralDirtyFree(inherited)
        world.removeRetainer(successorCard.id)
        for (card in outerCards)
            world.updateRetainer(card.copy(generalId = successor.id, name = successor.name))
        for (card in cards.filter { it.id != successorCard.id })
            world.updateRetainer(card.copy(masterGeneralId = successor.id))
        for (unit in world.listBugoks().filter { it.masterGeneralId == actorId })
            world.updateBugok(unit.copy(masterGeneralId = successor.id))
        if (nation != null) {
            val nextNation = nation.copy(chiefGeneralId = successor.id,
                meta = nation.meta + ("gennum" to world.listGenerals().count { it.nationId == nation.id && it.npcState != 5 }))
            recorder.diffNation(PerTurnOverlay.toLogicNation(nation), PerTurnOverlay.toLogicNation(nextNation))
            world.applyNationDirtyFree(nextNation)
        }
        HwihaRecords.general(world, actorId, RecordKind.PERSONAL_APPLIED,
            "${actor.name}이 ${successor.name}에게 휘하를 넘기고 은퇴했습니다.",
            mapOf("inputId" to HwihaRetireInput.INPUT_ID, "successorGeneralId" to successor.id,
                "requestId" to requestId))
        return HwihaTurnOutcome.Applied(HwihaRetireInput.INPUT_ID, effects)
    }

    companion object { private const val LAST_TURN_KEY = "hwihaRetireLastTurn" }
}
