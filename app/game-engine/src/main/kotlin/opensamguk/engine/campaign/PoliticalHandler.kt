package opensamguk.engine.campaign

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.renown.RenownEventSource

/** Nation-changing personal orders are resolved at the political stage before movement. */
class PoliticalHandler(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val context: DomesticContext,
    private val catalog: InputCatalog = InputCatalog.load()) {
    fun handle(inputId: String, actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?,
        npcSelected: Boolean = false): TurnOutcome {
        fun reject(reason: PoliticalFailure) = TurnOutcome.Rejected(inputId, reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(PoliticalFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(PoliticalFailure.ACTOR_NOT_FOUND)
        val npc = npcSelected && ownerUserId == null && actor.npcState >= 2 && NpcDeploySelector.isUnowned(actor.userId)
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return TurnOutcome.Rejected(inputId, "FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        val request = PoliticalInput.parse(actorId, inputId, rawJson)
            ?: return reject(PoliticalFailure.INVALID_INPUT)
        if (catalog[inputId]?.deliveryState?.hasHandler != true)
            return TurnOutcome.Rejected(inputId, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val turnToken = actor.turnTime.toString()
        val previous = actor.meta[LAST_TURN_KEY] as? Map<*, *>
        if (previous?.get("turn") == turnToken) {
            if (previous["inputId"] == inputId && previous["requestId"] == requestId)
                return TurnOutcome.Applied(inputId,
                    (previous["effects"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return reject(PoliticalFailure.ALREADY_PROCESSED)
        }
        val assessed = PoliticalRules.assess(request, context.projection(world))
        if (assessed is PoliticalAssessment.Rejected) return reject(assessed.reason)
        val ready = assessed as PoliticalAssessment.Eligible
        val county = ready.county?.let { world.getCityById(it.id) }
        if (inputId in setOf(PoliticalInput.RISE, PoliticalInput.INDEPENDENCE) && county == null)
            return reject(PoliticalFailure.COUNTY_UNAVAILABLE)
        val formerNation = actor.nationId
        val movingRetinue = inputId in setOf(PoliticalInput.RESIGN, PoliticalInput.RISE,
            PoliticalInput.INDEPENDENCE)
        val subtree = (if (movingRetinue) retinueTree(actorId) else emptyList())
            ?: return reject(PoliticalFailure.STATE_UNAVAILABLE)
        if (movingRetinue && subtree.any { world.getGeneralById(it)?.nationId != formerNation })
            return reject(PoliticalFailure.STATE_UNAVAILABLE)
        val oldNation = if (formerNation > 0) world.getNationById(formerNation) else null
        if (formerNation > 0 && oldNation == null) return reject(PoliticalFailure.STATE_UNAVAILABLE)
        val newNationId = if (inputId == PoliticalInput.RISE || inputId == PoliticalInput.INDEPENDENCE)
            world.allocateNationId() else 0
        val effects = mutableListOf<String>()
        when (inputId) {
            PoliticalInput.RESIGN -> {
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
            PoliticalInput.RISE, PoliticalInput.INDEPENDENCE -> {
                val seat = checkNotNull(county)
                val name = (actor.name + "군").take(24)
                val nation = Nation(newNationId, name, "#%06X".format((newNationId * 0x4F1BBC) and 0xFFFFFF),
                    capitalCityId = seat.id, chiefGeneralId = actorId,
                    meta = mapOf("gennum" to (1 + subtree.size), "capset" to 0))
                val others = world.listNations().map { it.id }
                world.createNation(nation)
                for (other in others) {
                    world.createDiplomacy(TurnDiplomacy(newNationId, other,
                        PoliticalDesign.CANON.initialDiplomacyState, 0))
                    world.createDiplomacy(TurnDiplomacy(other, newNationId,
                        PoliticalDesign.CANON.initialDiplomacyState, 0))
                }
                val nextSeat = seat.copy(nationId = newNationId)
                recorder.diffCity(PerTurnOverlay.toLogicCity(seat), PerTurnOverlay.toLogicCity(nextSeat))
                world.applyCityDirtyFree(nextSeat)
                if (inputId == PoliticalInput.INDEPENDENCE)
                    for (card in world.listRetainers().filter { it.generalId == actorId }) world.removeRetainer(card.id)
                changeAllegiance(listOf(actorId) + subtree, newNationId, lordId = actorId)
                if (formerNation > 0) {
                    CapitalAfterCapture(world, recorder).settle(formerNation, seat.id)
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
            PoliticalInput.DISSOLVE -> {
                val members = world.listGenerals().filter { it.nationId == formerNation }.map { it.id }
                recorder.markNationDeleted(world, formerNation)
                for (id in members) {
                    val current = world.getGeneralById(id) ?: continue
                    val next = current.copy(nationId = 0, officerLevel = 1,
                        meta = current.meta + (LordStatus.META_KEY to false))
                    recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(current), PerTurnOverlay.toLogicGeneral(next))
                    world.applyGeneralDirtyFree(next)
                }
                effects += "dissolvedNationId:$formerNation"
            }
            PoliticalInput.FOUND_STATE -> {
                val old = checkNotNull(oldNation)
                if (old.level > 0) return reject(PoliticalFailure.ALREADY_FOUNDED)
                val next = old.copy(level = 1, meta = old.meta + ("foundedBy" to actorId))
                recorder.diffNation(PerTurnOverlay.toLogicNation(old), PerTurnOverlay.toLogicNation(next))
                world.applyNationDirtyFree(next)
                effects += "nationLevel:1"
            }
            PoliticalInput.ABDICATE -> {
                val targetId = checkNotNull(request.targetGeneralId)
                val target = world.getGeneralById(targetId) ?: return reject(PoliticalFailure.TARGET_NOT_FOUND)
                val nation = oldNation ?: return reject(PoliticalFailure.STATE_UNAVAILABLE)
                for (card in world.listRetainers().filter { it.generalId == targetId }) world.removeRetainer(card.id)
                val oldRuler = actor.copy(officerLevel = 1,
                    meta = actor.meta + (LordStatus.META_KEY to false))
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(actor), PerTurnOverlay.toLogicGeneral(oldRuler))
                world.applyGeneralDirtyFree(oldRuler)
                val successor = target.copy(officerLevel = 12,
                    meta = (target.meta - PoliticalConsent.META_KEY) + (LordStatus.META_KEY to true))
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(target), PerTurnOverlay.toLogicGeneral(successor))
                world.applyGeneralDirtyFree(successor)
                val nextNation = nation.copy(chiefGeneralId = targetId)
                recorder.diffNation(PerTurnOverlay.toLogicNation(nation), PerTurnOverlay.toLogicNation(nextNation))
                world.applyNationDirtyFree(nextNation)
                effects += "chiefGeneralId:$targetId"
            }
            PoliticalInput.OATH -> {
                val targetId = checkNotNull(request.targetGeneralId)
                val target = world.getGeneralById(targetId) ?: return reject(PoliticalFailure.TARGET_NOT_FOUND)
                val nextActor = actor.copy(meta = OathBonds.withBond(actor.meta, targetId))
                val nextTarget = target.copy(meta = OathBonds.withBond(target.meta - PoliticalConsent.META_KEY, actorId))
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(actor), PerTurnOverlay.toLogicGeneral(nextActor))
                world.applyGeneralDirtyFree(nextActor)
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(target), PerTurnOverlay.toLogicGeneral(nextTarget))
                world.applyGeneralDirtyFree(nextTarget)
                effects += "oathGeneralId:$targetId"
            }
            else -> return reject(PoliticalFailure.INVALID_INPUT)
        }
        val latest = world.getGeneralById(actorId) ?: return reject(PoliticalFailure.STATE_UNAVAILABLE)
        val stamp = mapOf("turn" to turnToken, "inputId" to inputId, "requestId" to requestId, "effects" to effects)
        val recorded = latest.copy(meta = latest.meta + (LAST_TURN_KEY to stamp))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(latest), PerTurnOverlay.toLogicGeneral(recorded))
        world.applyGeneralDirtyFree(recorded)
        if (inputId == PoliticalInput.OATH) {
            val renown = RenownEventRecorder(world, recorder)
            renown.record(actorId, RenownEventSource.SWORN_OATH)
            renown.record(checkNotNull(request.targetGeneralId), RenownEventSource.SWORN_OATH)
        }
        Records.general(world, actorId, RecordKind.PERSONAL_APPLIED,
            "${actor.name}의 정치 행동을 마쳤습니다.", mapOf("inputId" to inputId, "requestId" to requestId))
        return TurnOutcome.Applied(inputId, effects)
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
                meta = current.meta + (LordStatus.META_KEY to (id == lordId)))
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(current), PerTurnOverlay.toLogicGeneral(next))
            world.applyGeneralDirtyFree(next)
        }
    }

    companion object { private const val LAST_TURN_KEY = "politicalLastTurn" }
}
