package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.*
import opensamguk.gameapi.dto.*
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class HwihaDispatchPrecheckService(
    private val generals: GeneralReadRepository,
    private val retainers: RetainerReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
) {
    fun assessDispatch(request: DispatchRequest, ownerUserId: Long): DispatchAssessment {
        owned(request.actorId, ownerUserId)
        val snapshot = snapshot()
        snapshot.failure?.let { return DispatchAssessment.Rejected(it) }
        return try {
            val actor = snapshot.state!!.people.single { it.id == request.actorId }
            if (QueuedDispatch.read(actor.meta) != null) DispatchAssessment.Rejected(DispatchFailure.ALREADY_QUEUED)
            else DispatchRules.assess(request, snapshot.state)
        } catch (_: IllegalArgumentException) { DispatchAssessment.Rejected(DispatchFailure.STATE_UNAVAILABLE) }
    }

    fun assessReply(request: DispatchReplyRequest, ownerUserId: Long): DispatchAssessment {
        owned(request.actorId, ownerUserId)
        val snapshot = snapshot()
        return snapshot.failure?.let { DispatchAssessment.Rejected(it) }
            ?: DispatchRules.assessReply(request, snapshot.now!!, snapshot.state!!)
    }

    fun options(actorId: Int, ownerUserId: Long, targetGeneralId: Int? = null): DispatchOptionsResponse {
        owned(actorId, ownerUserId)
        val snapshot = snapshot()
        snapshot.failure?.let { return DispatchOptionsResponse(false, it) }
        val state = snapshot.state!!
        val actor = state.people.singleOrNull { it.id == actorId }
            ?: return DispatchOptionsResponse(false, DispatchFailure.ACTOR_NOT_FOUND)
        if (!actor.isLord || actor.nationId <= 0) return DispatchOptionsResponse(false, DispatchFailure.NOT_LORD)
        return try {
            val queue = QueuedDispatch.read(actor.meta)
            val targets = state.people.filter { person ->
                person.id != actorId && person.isHuman && !person.isLord && person.nationId == actor.nationId &&
                    state.retainers.filter { it.generalId == person.id }.singleOrNull()?.masterId == actorId
            }.sortedBy { it.id }.map { DispatchTargetOption(it.id, snapshot.personNames.getValue(it.id)) }
            // Do not expose unrelated people or their dispatch availability through arbitrary target IDs.
            if (targetGeneralId != null && targets.none { it.generalId == targetGeneralId })
                return DispatchOptionsResponse(false, DispatchFailure.NOT_DIRECT_RETAINER)
            val counties = if (targetGeneralId == null) emptyList() else state.counties
                .filter { it.nationId == actor.nationId }.sortedBy { it.id }.map { county ->
                    val failure = if (queue != null) DispatchFailure.ALREADY_QUEUED else
                        (DispatchRules.assess(DispatchRequest(actorId, targetGeneralId, county.id), state)
                            as? DispatchAssessment.Rejected)?.reason
                    DispatchCountyOption(county.id, snapshot.countyNames.getValue(county.id), failure == null,
                        failure, failure?.message)
                }
            DispatchOptionsResponse(true, if (queue != null) DispatchFailure.ALREADY_QUEUED else null,
                now = snapshot.now, targets = targets, counties = counties, queued = ownedQueue(queue, ownerUserId))
        } catch (_: IllegalArgumentException) { DispatchOptionsResponse(false, DispatchFailure.STATE_UNAVAILABLE) }
    }

    private fun ownedQueue(queue: QueuedDispatch?, ownerUserId: Long): DispatchQueuedItem? =
        queue?.takeIf { it.ownerUserId.toLong() == ownerUserId }?.let {
            DispatchQueuedItem(it.requestId, it.targetGeneralId, it.countyId)
        }

    fun pending(actorId: Int, ownerUserId: Long): DispatchPendingResponse {
        owned(actorId, ownerUserId)
        val snapshot = snapshot()
        if (snapshot.failure != null) return DispatchPendingResponse(false, snapshot.failure)
        val state = snapshot.state!!
        val direct = state.retainers.groupBy { it.generalId }.filterValues {
            it.size == 1 && it.single().masterId == actorId
        }.keys
        return try {
            val rows = state.people.filter { it.id == actorId || it.id in direct }.mapNotNull { person ->
                val dispatch = DispatchState.read(person.meta) ?: return@mapNotNull null
                if (dispatch.targetId != person.id) throw IllegalArgumentException("Dispatch recipient identity mismatch")
                if (person.id != actorId && dispatch.issuerId != actorId) return@mapNotNull null
                val assessment = DispatchRules.assessReply(person.id, dispatch.dispatchId, state)
                DispatchPendingItem(dispatch.dispatchId, dispatch.issuerId, dispatch.targetId,
                    dispatch.countyId, dispatch.issuedAt, dispatch.dueAt, dispatch.status,
                    (assessment as? DispatchAssessment.Rejected)?.reason, snapshot.personNames[dispatch.issuerId],
                    snapshot.personNames[dispatch.targetId], snapshot.countyNames[dispatch.countyId])
            }.sortedBy { it.targetId }
            val queue = QueuedDispatch.read(state.people.single { it.id == actorId }.meta)
            DispatchPendingResponse(true, now = snapshot.now, dispatches = rows, queued = ownedQueue(queue, ownerUserId))
        } catch (_: IllegalArgumentException) { DispatchPendingResponse(false, DispatchFailure.STATE_UNAVAILABLE) }
    }

    private fun owned(actorId: Int, userId: Long) {
        if (actorId <= 0 || userId <= 0 || userId > Int.MAX_VALUE.toLong()) throw DispatchReadForbidden()
        if (generals.findById(actorId).orElse(null)?.userId?.toLongOrNull() != userId) throw DispatchReadForbidden()
    }

    private data class Snapshot(val state: DispatchProjection? = null, val now: Phase? = null,
        val failure: DispatchFailure? = null, val personNames: Map<Int, String> = emptyMap(),
        val countyNames: Map<Int, String> = emptyMap())

    private fun snapshot(): Snapshot = try {
        val selected = artifacts.resolve()
        if (selected == null) Snapshot(failure = DispatchFailure.STATE_UNAVAILABLE)
        else {
            val config = selected.world.config
            val profile = opensamguk.logic.input.WorldRuleProfile.require(config)
            if (profile != RuleProfile.HWIHA) Snapshot(failure = DispatchFailure.WRONG_RULE_PROFILE)
            else {
                val resolved = requireNotNull(selected.artifacts) { "HWIHA requires pinned Han artifacts" }
                val people = generals.findAll()
                val cards = retainers.findAll()
                require(people.all { it.worldId == selected.world.id } && cards.all { it.worldId == selected.world.id })
                require(people.map { it.id }.distinct().size == people.size && cards.map { it.id }.distinct().size == cards.size)
                Snapshot(DispatchProjection(profile,
                    people.map { DispatchPerson(it.id, it.nationId, LordStatus.read(it.meta),
                        (it.userId?.toLongOrNull() ?: 0) > 0, it.meta) },
                    cards.mapNotNull { card -> card.generalId?.let { DispatchRetainer(card.id, card.masterGeneralId, it, card.loyalty) } },
                    selected.cities.filter { it.id in resolved.projection.administrativeCountyIds }
                        .map { DispatchCounty(it.id, it.nationId) }),
                    Phase(selected.world.currentYear, selected.world.currentMonth, selected.world.currentPhase),
                    personNames = people.associate { it.id to it.name },
                    countyNames = selected.cities.associate { it.id to it.name })
            }
        }
    } catch (_: IllegalArgumentException) { Snapshot(failure = DispatchFailure.STATE_UNAVAILABLE) }
      catch (_: IllegalStateException) { Snapshot(failure = DispatchFailure.STATE_UNAVAILABLE) }
      catch (_: java.io.IOException) { Snapshot(failure = DispatchFailure.STATE_UNAVAILABLE) }
}

class DispatchReadForbidden : RuntimeException()
