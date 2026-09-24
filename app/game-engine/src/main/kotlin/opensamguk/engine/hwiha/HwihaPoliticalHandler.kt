package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*

/** Nation-changing personal orders are resolved at the political stage before movement. */
class HwihaPoliticalHandler(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val context: HwihaDomesticContext) {
    fun handle(inputId: String, actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?,
        npcSelected: Boolean = false): HwihaTurnOutcome {
        fun reject(reason: HwihaPoliticalFailure) = HwihaTurnOutcome.Rejected(inputId, reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(HwihaPoliticalFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(HwihaPoliticalFailure.ACTOR_NOT_FOUND)
        val npc = npcSelected && ownerUserId == null && actor.npcState >= 2 && HwihaNpcDeploySelector.isUnowned(actor.userId)
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return HwihaTurnOutcome.Rejected(inputId, "FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        val request = HwihaPoliticalInput.parse(actorId, inputId, rawJson)
            ?: return reject(HwihaPoliticalFailure.INVALID_INPUT)
        val turnToken = actor.turnTime.toString()
        val previous = actor.meta[LAST_TURN_KEY] as? Map<*, *>
        if (previous?.get("turn") == turnToken) {
            if (previous["inputId"] == inputId && previous["requestId"] == requestId)
                return HwihaTurnOutcome.Applied(inputId,
                    (previous["effects"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return reject(HwihaPoliticalFailure.ALREADY_PROCESSED)
        }
        val assessed = HwihaPoliticalRules.assess(request, context.projection(world))
        if (assessed is HwihaPoliticalAssessment.Rejected) return reject(assessed.reason)
        val ready = assessed as HwihaPoliticalAssessment.Eligible
        val county = ready.county?.let { world.getCityById(it.id) }
        if (inputId in setOf(HwihaPoliticalInput.RISE, HwihaPoliticalInput.INDEPENDENCE) && county == null)
            return reject(HwihaPoliticalFailure.COUNTY_UNAVAILABLE)
        val formerNation = actor.nationId
        val subtree = if (inputId == HwihaPoliticalInput.DISSOLVE) emptyList() else retinueTree(actorId)
            ?: return reject(HwihaPoliticalFailure.STATE_UNAVAILABLE)
        if (subtree.any { world.getGeneralById(it)?.nationId != formerNation })
            return reject(HwihaPoliticalFailure.STATE_UNAVAILABLE)
        val oldNation = if (formerNation > 0) world.getNationById(formerNation) else null
        if (formerNation > 0 && oldNation == null) return reject(HwihaPoliticalFailure.STATE_UNAVAILABLE)
        val newNationId = if (inputId == HwihaPoliticalInput.RISE || inputId == HwihaPoliticalInput.INDEPENDENCE)
            world.allocateNationId() else 0
        val effects = mutableListOf<String>()
        when (inputId) {
            HwihaPoliticalInput.RESIGN -> {
                for (card in world.listRetainers().filter { it.generalId == actorId }) world.removeRetainer(card.id)
                changeAllegiance(listOf(actorId) + subtree, 0, lordId = null)
                oldNation?.let { old ->
                    val next = old.copy(meta = old.meta + ("gennum" to world.listGenerals().count {
                        it.nationId == formerNation && it.npcState != 5 }))
                    recorder.diffNation(PerTurnOverlay.toLogicNation(old), PerTurnOverlay.toLogicNation(next))
                    world.applyNationDirtyFree(next)
                }
                effects += "nationId:0"
            }
            HwihaPoliticalInput.RISE, HwihaPoliticalInput.INDEPENDENCE -> {
                val seat = checkNotNull(county)
                val name = (actor.name + "군").take(24)
                val nation = Nation(newNationId, name, "#%06X".format((newNationId * 0x4F1BBC) and 0xFFFFFF),
                    capitalCityId = seat.id, chiefGeneralId = actorId,
                    meta = mapOf("gennum" to (1 + subtree.size), "capset" to 0))
                val others = world.listNations().map { it.id }
                world.createNation(nation)
                for (other in others) {
                    world.createDiplomacy(TurnDiplomacy(newNationId, other, 0, 0))
                    world.createDiplomacy(TurnDiplomacy(other, newNationId, 0, 0))
                }
                val nextSeat = seat.copy(nationId = newNationId)
                recorder.diffCity(PerTurnOverlay.toLogicCity(seat), PerTurnOverlay.toLogicCity(nextSeat))
                world.applyCityDirtyFree(nextSeat)
                if (inputId == HwihaPoliticalInput.INDEPENDENCE)
                    for (card in world.listRetainers().filter { it.generalId == actorId }) world.removeRetainer(card.id)
                changeAllegiance(listOf(actorId) + subtree, newNationId, lordId = actorId)
                if (formerNation > 0) {
                    HwihaCapitalAfterCapture(world, recorder).settle(formerNation, seat.id)
                    world.getNationById(formerNation)?.let { old ->
                        val next = old.copy(meta = old.meta + ("gennum" to world.listGenerals().count {
                            it.nationId == formerNation && it.npcState != 5 }))
                        recorder.diffNation(PerTurnOverlay.toLogicNation(old), PerTurnOverlay.toLogicNation(next))
                        world.applyNationDirtyFree(next)
                    }
                }
                effects += "nationId:$newNationId"
                effects += "countyId:${seat.id}"
            }
            HwihaPoliticalInput.DISSOLVE -> {
                val members = world.listGenerals().filter { it.nationId == formerNation }.map { it.id }
                recorder.markNationDeleted(world, formerNation)
                for (id in members) {
                    val current = world.getGeneralById(id) ?: continue
                    val next = current.copy(nationId = 0, officerLevel = 1,
                        meta = current.meta + (HwihaLordStatus.META_KEY to false))
                    recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(current), PerTurnOverlay.toLogicGeneral(next))
                    world.applyGeneralDirtyFree(next)
                }
                effects += "dissolvedNationId:$formerNation"
            }
            HwihaPoliticalInput.FOUND_STATE -> {
                val old = checkNotNull(oldNation)
                if (old.level > 0) return reject(HwihaPoliticalFailure.ALREADY_FOUNDED)
                val next = old.copy(level = 1, meta = old.meta + ("hwihaFoundedBy" to actorId))
                recorder.diffNation(PerTurnOverlay.toLogicNation(old), PerTurnOverlay.toLogicNation(next))
                world.applyNationDirtyFree(next)
                effects += "nationLevel:1"
            }
            HwihaPoliticalInput.ABDICATE -> {
                val targetId = checkNotNull(request.targetGeneralId)
                val target = world.getGeneralById(targetId) ?: return reject(HwihaPoliticalFailure.TARGET_NOT_FOUND)
                val nation = oldNation ?: return reject(HwihaPoliticalFailure.STATE_UNAVAILABLE)
                for (card in world.listRetainers().filter { it.generalId == targetId }) world.removeRetainer(card.id)
                val oldRuler = actor.copy(officerLevel = 1,
                    meta = actor.meta + (HwihaLordStatus.META_KEY to false))
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(actor), PerTurnOverlay.toLogicGeneral(oldRuler))
                world.applyGeneralDirtyFree(oldRuler)
                val successor = target.copy(officerLevel = 12,
                    meta = (target.meta - HwihaPoliticalConsent.META_KEY) + (HwihaLordStatus.META_KEY to true))
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(target), PerTurnOverlay.toLogicGeneral(successor))
                world.applyGeneralDirtyFree(successor)
                val nextNation = nation.copy(chiefGeneralId = targetId)
                recorder.diffNation(PerTurnOverlay.toLogicNation(nation), PerTurnOverlay.toLogicNation(nextNation))
                world.applyNationDirtyFree(nextNation)
                effects += "chiefGeneralId:$targetId"
            }
            HwihaPoliticalInput.OATH -> {
                val targetId = checkNotNull(request.targetGeneralId)
                val target = world.getGeneralById(targetId) ?: return reject(HwihaPoliticalFailure.TARGET_NOT_FOUND)
                val nextActor = actor.copy(meta = HwihaOathBonds.withBond(actor.meta, targetId))
                val nextTarget = target.copy(meta = HwihaOathBonds.withBond(target.meta - HwihaPoliticalConsent.META_KEY, actorId))
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(actor), PerTurnOverlay.toLogicGeneral(nextActor))
                world.applyGeneralDirtyFree(nextActor)
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(target), PerTurnOverlay.toLogicGeneral(nextTarget))
                world.applyGeneralDirtyFree(nextTarget)
                effects += "oathGeneralId:$targetId"
            }
            else -> return reject(HwihaPoliticalFailure.INVALID_INPUT)
        }
        val latest = world.getGeneralById(actorId) ?: return reject(HwihaPoliticalFailure.STATE_UNAVAILABLE)
        val stamp = mapOf("turn" to turnToken, "inputId" to inputId, "requestId" to requestId, "effects" to effects)
        val recorded = latest.copy(meta = latest.meta + (LAST_TURN_KEY to stamp))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(latest), PerTurnOverlay.toLogicGeneral(recorded))
        world.applyGeneralDirtyFree(recorded)
        if (inputId == HwihaPoliticalInput.OATH) {
            val renown = HwihaRenownEventRecorder(world, recorder)
            renown.record(actorId, HwihaRenownEventSource.SWORN_OATH)
            renown.record(checkNotNull(request.targetGeneralId), HwihaRenownEventSource.SWORN_OATH)
        }
        HwihaRecords.general(world, actorId, HwihaRecordKind.PERSONAL_APPLIED,
            "${actor.name}의 정치 행동을 마쳤습니다.", mapOf("inputId" to inputId, "requestId" to requestId))
        return HwihaTurnOutcome.Applied(inputId, effects)
    }

    private fun retinueTree(masterId: Int): List<Int>? {
        val seen = mutableSetOf(masterId)
        val queue = ArrayDeque<Int>()
        queue.add(masterId)
        val descendants = mutableListOf<Int>()
        val cards = world.listRetainers()
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            for (child in cards.filter { it.masterGeneralId == parent }.mapNotNull { it.generalId }) {
                if (!seen.add(child)) return null
                descendants += child
                queue.add(child)
            }
        }
        return descendants
    }

    private fun changeAllegiance(ids: List<Int>, nationId: Int, lordId: Int?) {
        for (id in ids) {
            val current = checkNotNull(world.getGeneralById(id))
            val next = current.copy(nationId = nationId,
                officerLevel = if (id == lordId) 12 else if (current.officerLevel == 12) 1 else current.officerLevel,
                meta = current.meta + (HwihaLordStatus.META_KEY to (id == lordId)))
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(current), PerTurnOverlay.toLogicGeneral(next))
            world.applyGeneralDirtyFree(next)
        }
    }

    companion object { private const val LAST_TURN_KEY = "hwihaPoliticalLastTurn" }
}
