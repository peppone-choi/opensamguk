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
        return snapshot.failure?.let { DispatchAssessment.Rejected(it) }
            ?: HwihaDispatchRules.assess(request, snapshot.state!!)
    }

    fun assessReply(request: DispatchReplyRequest, ownerUserId: Long): DispatchAssessment {
        owned(request.actorId, ownerUserId)
        val snapshot = snapshot()
        return snapshot.failure?.let { DispatchAssessment.Rejected(it) }
            ?: HwihaDispatchRules.assessReply(request, snapshot.now!!, snapshot.state!!)
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
                val dispatch = HwihaDispatchState.read(person.meta) ?: return@mapNotNull null
                if (dispatch.targetId != person.id) throw IllegalArgumentException("Dispatch recipient identity mismatch")
                if (person.id != actorId && dispatch.issuerId != actorId) return@mapNotNull null
                val assessment = HwihaDispatchRules.assessReply(person.id, dispatch.dispatchId, state)
                DispatchPendingItem(dispatch.dispatchId, dispatch.issuerId, dispatch.targetId,
                    dispatch.countyId, dispatch.issuedAt, dispatch.dueAt, dispatch.status,
                    (assessment as? DispatchAssessment.Rejected)?.reason)
            }.sortedBy { it.targetId }
            DispatchPendingResponse(true, now = snapshot.now, dispatches = rows)
        } catch (_: IllegalArgumentException) { DispatchPendingResponse(false, DispatchFailure.STATE_UNAVAILABLE) }
    }

    private fun owned(actorId: Int, userId: Long) {
        if (actorId <= 0 || userId <= 0 || userId > Int.MAX_VALUE.toLong()) throw DispatchReadForbidden()
        if (generals.findById(actorId).orElse(null)?.userId?.toLongOrNull() != userId) throw DispatchReadForbidden()
    }

    private data class Snapshot(val state: HwihaDispatchProjection? = null, val now: HwihaPhase? = null,
        val failure: DispatchFailure? = null)

    private fun snapshot(): Snapshot = try {
        val selected = artifacts.resolve()
        if (selected == null) Snapshot(failure = DispatchFailure.STATE_UNAVAILABLE)
        else {
            val config = selected.world.config
            val profile = when {
                "ruleProfile" !in config -> RuleProfile.SAMMO
                config["ruleProfile"] == "SAMMO" -> RuleProfile.SAMMO
                config["ruleProfile"] == "HWIHA" -> RuleProfile.HWIHA
                else -> throw IllegalArgumentException("Invalid rule profile")
            }
            if (profile != RuleProfile.HWIHA) Snapshot(failure = DispatchFailure.WRONG_RULE_PROFILE)
            else {
                val resolved = requireNotNull(selected.artifacts) { "HWIHA requires pinned Han artifacts" }
                val people = generals.findAll()
                val cards = retainers.findAll()
                require(people.all { it.worldId == selected.world.id } && cards.all { it.worldId == selected.world.id })
                require(people.map { it.id }.distinct().size == people.size && cards.map { it.id }.distinct().size == cards.size)
                Snapshot(HwihaDispatchProjection(profile,
                    people.map { DispatchPerson(it.id, it.nationId, HwihaLordStatus.read(it.meta),
                        (it.userId?.toLongOrNull() ?: 0) > 0, it.meta) },
                    cards.mapNotNull { card -> card.generalId?.let { DispatchRetainer(card.id, card.masterGeneralId, it, card.loyalty) } },
                    selected.cities.filter { it.id in resolved.projection.administrativeCountyIds }
                        .map { DispatchCounty(it.id, it.nationId) }),
                    HwihaPhase(selected.world.currentYear, selected.world.currentMonth, selected.world.currentPhase))
            }
        }
    } catch (_: IllegalArgumentException) { Snapshot(failure = DispatchFailure.STATE_UNAVAILABLE) }
      catch (_: IllegalStateException) { Snapshot(failure = DispatchFailure.STATE_UNAVAILABLE) }
      catch (_: java.io.IOException) { Snapshot(failure = DispatchFailure.STATE_UNAVAILABLE) }
}

class DispatchReadForbidden : RuntimeException()
