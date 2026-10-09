package opensamguk.engine.campaign

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.council.CurrentRulerBinding
import opensamguk.logic.domestic.CorpsPolicyAssignments
import opensamguk.logic.domestic.PlacementMarch
import opensamguk.logic.domestic.PlacementState
import opensamguk.logic.renown.RenownEventSource
import opensamguk.logic.vision.ScoutPosts

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
        val resignation = if (inputId in setOf(PoliticalInput.RESIGN, PoliticalInput.RISE))
            prepareResignation((listOf(actorId) + subtree).toSet())
                ?: return reject(PoliticalFailure.STATE_UNAVAILABLE)
            else null
        if (resignation != null && oldNation?.chiefGeneralId?.let { it in resignation.movingIds } == true)
            return reject(PoliticalFailure.STATE_UNAVAILABLE)
        val newNationId = if (inputId == PoliticalInput.RISE || inputId == PoliticalInput.INDEPENDENCE)
            world.allocateNationId() else 0
        val effects = mutableListOf<String>()
        when (inputId) {
            PoliticalInput.RESIGN -> {
                val plan = checkNotNull(resignation)
                for (card in world.listRetainers().filter { it.generalId?.let(plan.movingIds::contains) == true &&
                    it.masterGeneralId !in plan.movingIds }.sortedBy { it.id }) world.removeRetainer(card.id)
                executeResignation(plan)
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
                    meta = rulerBinding(mapOf("gennum" to (1 + subtree.size), "capset" to 0),
                        actorId, requestId, inputId))
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
                if (inputId == PoliticalInput.RISE) executeResignation(checkNotNull(resignation), newNationId)
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
                val nextNation = nation.copy(chiefGeneralId = targetId,
                    meta = rulerBinding(nation.meta, targetId, requestId, inputId))
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

    private data class Resignation(
        val movingIds: Set<Int>,
        val releasedOrders: Set<String>,
        val deployments: Map<Int, List<DeployedCorps>>,
        val policies: Map<Int, CorpsPolicyAssignments>,
        val scoutOwners: Set<Int>,
    )

    /** Resolve every reference before changing allegiance; corrupt military state must have no effects. */
    private fun prepareResignation(movingIds: Set<Int>): Resignation? {
        val people = world.listGenerals()
        if (people.any { it.id in movingIds && CorpsEncounter.META_KEY in it.meta } ||
            movingIds.any { world.generalPositionSnapshot()?.stateFor(it)?.battlefield != null } ||
            world.listSieges().any { it.status == SiegeService.ACTIVE && it.besiegerGeneralId in movingIds }) return null
        val deployments = mutableMapOf<Int, List<DeployedCorps>>()
        val policies = mutableMapOf<Int, CorpsPolicyAssignments>()
        val roadForts: List<RoadFort>
        val scoutOwners = world.listRetainers().filter { it.generalId?.let(movingIds::contains) == true &&
            it.masterGeneralId !in movingIds }.mapTo(mutableSetOf()) { it.masterGeneralId }
        try {
            roadForts = RoadFortState.read(world.getState().meta)
            for (person in people) {
                DeploymentState.read(person.meta)?.let { deployments[person.id] = it.corps }
                CorpsPolicyAssignments.read(person.meta)?.let { policies[person.id] = it }
                if (person.id in movingIds) PlacementState.read(person.meta)?.let { placement ->
                    listOfNotNull(placement.active?.order, placement.pending).forEach {
                        scoutOwners += it.ownerGeneralId
                    }
                }
            }
            if (deployments.values.flatten().any { world.getGeneralById(it.commanderGeneralId) == null ||
                    world.getGeneralById(it.ownerGeneralId) == null }) return null
            if (deployments.isNotEmpty()) MarchReactions.read(world.getState().meta)
        } catch (_: IllegalArgumentException) { return null }
        val ownedBugoks = world.listBugoks().filter { it.masterGeneralId in movingIds }.mapTo(hashSetOf()) { it.id }
        val released = deployments.values.flatten().filter { it.ownerGeneralId in movingIds ||
            it.commanderGeneralId in movingIds || it.bugokIds.any(ownedBugoks::contains) }
            .mapTo(hashSetOf()) { it.orderId }
        val affectedCommanders = deployments.values.flatten().filter { it.orderId in released }
            .mapTo(movingIds.toMutableSet()) { it.commanderGeneralId }
        if (roadForts.any { it.besiegerGeneralId in affectedCommanders }) return null
        return Resignation(movingIds, released, deployments, policies, scoutOwners)
    }

    /** Rise recalls former orders but transfers troops; resignation retains its existing dissolution rules. */
    private fun executeResignation(plan: Resignation, risingNationId: Int? = null) {
        val people = world.listGenerals().sortedBy { it.id }
        val leaders = people.filter { it.id in plan.movingIds && it.troopId == it.id }
            .mapTo(hashSetOf()) { it.id }
        val releasedCommanders = plan.deployments.values.flatten().filter { it.orderId in plan.releasedOrders }
            .mapTo(hashSetOf()) { it.commanderGeneralId }
        for (person in people) {
            val moving = person.id in plan.movingIds
            val oldCorps = plan.deployments[person.id]
            val remainingCorps = oldCorps?.filterNot { it.orderId in plan.releasedOrders }
            val oldPolicies = plan.policies[person.id]
            val remainingPolicies = oldPolicies?.entries?.filterNot {
                moving || it.orderId in plan.releasedOrders || it.commanderGeneralId in plan.movingIds
            }
            var meta = person.meta
            if (oldCorps != null && remainingCorps != oldCorps)
                meta = meta.withKey(DeploymentState.META_KEY,
                    remainingCorps?.takeIf { it.isNotEmpty() }?.let { DeploymentState(it).toMetaValue() })
            if (oldPolicies != null && remainingPolicies != oldPolicies.entries)
                meta = meta.withKey(CorpsPolicyAssignments.META_KEY,
                    remainingPolicies?.takeIf { it.isNotEmpty() }?.let { CorpsPolicyAssignments(it).toMetaValue() })
            if (person.id in releasedCommanders) meta = meta - CorpsOrder.META_KEY - CorpsMarchState.META_KEY
            if (moving) meta = (meta - DeploymentState.META_KEY - CorpsPolicyAssignments.META_KEY -
                CorpsOrder.META_KEY - CorpsMarchState.META_KEY - PlacementState.META_KEY -
                PlacementMarch.META_KEY - CountyAssignment.META_KEY - DispatchState.META_KEY -
                QueuedCourtAction.META_KEY - QueuedDispatch.META_KEY - ScoutPosts.META_KEY) +
                mapOf(LordStatus.META_KEY to false, "officer_city" to 0, "belong" to 0, "permission" to "normal")
            if (moving && risingNationId == null) meta = meta + ("makelimit" to 12)
            val next = person.copy(nationId = if (moving) risingNationId ?: 0 else person.nationId,
                officerLevel = if (moving) 0 else person.officerLevel,
                troopId = if (risingNationId == null && (moving || person.troopId in leaders)) 0 else person.troopId, meta = meta)
            if (next != person) {
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(person), PerTurnOverlay.toLogicGeneral(next))
                world.applyGeneralDirtyFree(next)
            }
        }
        if (risingNationId == null) leaders.sorted().forEach(world::removeTroop)
        else leaders.sorted().forEach { id ->
            world.updateTroop(checkNotNull(world.getTroopById(id)).copy(nationId = risingNationId))
        }
        world.listOperationUnits().filter { it.generalId in plan.movingIds ||
            it.bugokId?.let { id -> world.getBugokById(id)?.masterGeneralId?.let(plan.movingIds::contains) } == true }
            .sortedBy { it.id }.forEach { world.removeOperationUnit(it.id) }
        world.listOperations().filter { it.declaredByGeneralId?.let(plan.movingIds::contains) == true }.sortedBy { it.id }
            .forEach { world.updateOperation(it.copy(declaredByGeneralId = null)) }
        world.listBattlePlans().filter { it.generalId in plan.movingIds }.sortedBy { it.id }
            .forEach { world.removeBattlePlan(it.id) }
        plan.scoutOwners.sorted().forEach { world.syncScoutPosts(recorder, it) }
        if (plan.releasedOrders.isNotEmpty()) ReactionInventory(world, recorder).rebuild()
        // officerSet and chief_set are quarterly appointment locks, not current office occupancy.
    }

    /** Preserve political action results without inventing a missing receipt. */
    private fun rulerBinding(meta: Map<String, Any?>, id: Int, requestId: String?, inputId: String): Map<String, Any?> =
        if (requestId != null && requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
            CurrentRulerBinding.with(meta, id, requestId, inputId)
        else meta - CurrentRulerBinding.META_KEY

    companion object { private const val LAST_TURN_KEY = "politicalLastTurn" }
}
