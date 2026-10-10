package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.input.*
import opensamguk.logic.council.CurrentRulerBinding
import java.time.Instant

/** Political-phase retirement transfers the personal retinue to the named direct retainer. */
class RetireHandler(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val context: DomesticContext,
    private val catalog: InputCatalog = InputCatalog.load()) {
    fun handle(actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?, npcSelected: Boolean = false): TurnOutcome {
        fun reject(reason: RetireFailure) = TurnOutcome.Rejected(RetireInput.INPUT_ID, reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(RetireFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(RetireFailure.ACTOR_NOT_FOUND)
        val request = RetireInput.parse(actorId, rawJson) ?: return reject(RetireFailure.INVALID_INPUT)
        if (actor.meta["retired"] == true || actor.npcState == 5)
            return replayRetired(actor, request, requestId, ownerUserId)
        val turnToken = actor.turnTime.toString()
        val npc = npcSelected && ownerUserId == null && actor.npcState >= 2 && NpcDeploySelector.isUnowned(actor.userId)
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return TurnOutcome.Rejected(RetireInput.INPUT_ID, "FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        if (catalog[RetireInput.INPUT_ID]?.deliveryState?.hasHandler != true)
            return TurnOutcome.Rejected(RetireInput.INPUT_ID,
                InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val assessed = RetireRules.assess(request, context.projection(world))
        if (assessed is RetireAssessment.Rejected) return reject(assessed.reason)
        val ready = assessed as RetireAssessment.Eligible
        val successor = world.getGeneralById(ready.successor.id) ?: return reject(RetireFailure.SUCCESSOR_UNAVAILABLE)
        val cards = world.listRetainers().filter { it.masterGeneralId == actorId }
        val outerCards = world.listRetainers().filter { it.generalId == actorId }
        if (outerCards.size > 1 || outerCards.any { it.masterGeneralId == request.successorGeneralId })
            return reject(RetireFailure.STATE_UNAVAILABLE)
        val successorCard = cards.singleOrNull { it.id == ready.successorCard.id }
            ?: return reject(RetireFailure.SUCCESSOR_NOT_RETAINER)
        if (cards.any { it.generalId != null && world.getGeneralById(it.generalId)?.nationId != actor.nationId })
            return reject(RetireFailure.STATE_UNAVAILABLE)
        val transferred = try {
            val from = PortableStock.read(actor.meta, actor.gold, actor.rice)
            val to = PortableStock.read(successor.meta, successor.gold, successor.rice)
            to.credit(from).also {
                PortableStock.checkedColumn(it.money)
                PortableStock.checkedColumn(it.grain)
            }
        } catch (_: IllegalArgumentException) { return reject(RetireFailure.STATE_UNAVAILABLE) }
          catch (_: ArithmeticException) { return reject(RetireFailure.STATE_UNAVAILABLE) }
        val nation = if (ready.wasLord) world.getNationById(actor.nationId)
            ?: return reject(RetireFailure.STATE_UNAVAILABLE) else null
        if (nation != null && nation.chiefGeneralId != null && nation.chiefGeneralId != actorId)
            return reject(RetireFailure.STATE_UNAVAILABLE)
        val effects = listOf("successorGeneralId:${successor.id}", "retainers:${cards.size - 1}",
            "bugoks:${world.listBugoks().count { it.masterGeneralId == actorId }}")
        val stamp = mapOf("turn" to turnToken, "requestId" to requestId, "ownerUserId" to ownerUserId,
            "successorGeneralId" to request.successorGeneralId, "effects" to effects)
        val retired = actor.copy(userId = null, npcState = 5, officerLevel = 1, gold = 0, rice = 0,
            meta = PortableStock.withStock(actor.meta, opensamguk.logic.economy.Resources()) +
                (LordStatus.META_KEY to false) + ("retired" to true) + (LAST_TURN_KEY to stamp))
        val inherited = successor.copy(userId = actor.userId ?: successor.userId,
            npcState = if (actor.userId != null) 0 else successor.npcState,
            officerLevel = if (ready.wasLord) 12 else successor.officerLevel,
            gold = PortableStock.checkedColumn(transferred.money),
            rice = PortableStock.checkedColumn(transferred.grain),
            meta = PortableStock.withStock(successor.meta, transferred) +
                (LordStatus.META_KEY to ready.wasLord))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(actor), PerTurnOverlay.toLogicGeneral(retired))
        world.applyGeneralDirtyFree(retired)
        if (!NpcDeploySelector.isUnowned(actor.userId)) recorder.recordGeneralOwnerDelete(actorId)
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
            val changedMeta = nation.meta + ("gennum" to world.listGenerals().count { it.nationId == nation.id && it.npcState != 5 })
            val durableMeta = if (requestId != null && requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
                CurrentRulerBinding.with(changedMeta, successor.id, requestId, RetireInput.INPUT_ID)
            else changedMeta - CurrentRulerBinding.META_KEY
            val nextNation = nation.copy(chiefGeneralId = successor.id, meta = durableMeta)
            recorder.diffNation(PerTurnOverlay.toLogicNation(nation), PerTurnOverlay.toLogicNation(nextNation))
            world.applyNationDirtyFree(nextNation)
        }
        Records.general(world, actorId, RecordKind.PERSONAL_APPLIED,
            "${actor.name}이 ${successor.name}에게 휘하를 넘기고 은퇴했습니다.",
            mapOf("inputId" to RetireInput.INPUT_ID, "successorGeneralId" to successor.id,
                "requestId" to requestId))
        return TurnOutcome.Applied(RetireInput.INPUT_ID, effects)
    }

    /** The lifecycle advances turnTime after retirement; the persisted request remains its identity. */
    private fun replayRetired(actor: TurnGeneral, request: RetireRequest, requestId: String?,
        ownerUserId: Int?): TurnOutcome {
        fun reject(reason: RetireFailure) = TurnOutcome.Rejected(RetireInput.INPUT_ID, reason.name, reason.message)
        if (LAST_TURN_KEY !in actor.meta) return reject(RetireFailure.ALREADY_RETIRED)
        val stamp = actor.meta[LAST_TURN_KEY] as? Map<*, *> ?: return reject(RetireFailure.STATE_UNAVAILABLE)
        val turn = stamp["turn"] as? String ?: return reject(RetireFailure.STATE_UNAVAILABLE)
        try { Instant.parse(turn) } catch (_: java.time.format.DateTimeParseException) {
            return reject(RetireFailure.STATE_UNAVAILABLE)
        }
        if ("requestId" !in stamp || (stamp["requestId"] != null && stamp["requestId"] !is String) ||
            "ownerUserId" !in stamp || (stamp["ownerUserId"] != null &&
                (stamp["ownerUserId"] !is Int || (stamp["ownerUserId"] as Int) <= 0)))
            return reject(RetireFailure.STATE_UNAVAILABLE)
        val successorId = stamp["successorGeneralId"] as? Int ?: return reject(RetireFailure.STATE_UNAVAILABLE)
        if (successorId <= 0 || successorId == actor.id) return reject(RetireFailure.STATE_UNAVAILABLE)
        val effects = stamp["effects"] as? List<*> ?: return reject(RetireFailure.STATE_UNAVAILABLE)
        if (effects.size != 3 || effects[0] != "successorGeneralId:$successorId" ||
            !validCount(effects[1], "retainers:") || !validCount(effects[2], "bugoks:"))
            return reject(RetireFailure.STATE_UNAVAILABLE)
        if (requestId == null || ownerUserId == null || requestId != stamp["requestId"] ||
            ownerUserId != stamp["ownerUserId"] || request.successorGeneralId != successorId)
            return reject(RetireFailure.ALREADY_RETIRED)
        return TurnOutcome.Applied(RetireInput.INPUT_ID, effects.filterIsInstance<String>())
    }

    private fun validCount(value: Any?, prefix: String): Boolean {
        val text = value as? String ?: return false
        if (!text.startsWith(prefix)) return false
        val count = text.removePrefix(prefix).toIntOrNull() ?: return false
        return count >= 0 && text == "$prefix$count"
    }

    companion object { private const val LAST_TURN_KEY = "retireLastTurn" }
}
