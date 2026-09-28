package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.RecordKind
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventKey
import opensamguk.logic.record.EventKind

/** Persists a transition before emitting its private event, so retries cannot duplicate it. */
internal class OfflineDelegationTransition(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) {
    fun update(generalId: Int, ownerUserId: Int, active: Boolean, phase: DelegationPhase) {
        val general = world.getGeneralById(generalId) ?: return
        if (general.userId?.toIntOrNull() != ownerUserId) return
        val old = general.meta[META_KEY] as? Map<*, *>
        val previous = if (old != null && old.keys == setOf("version", "ownerUserId", "active") && old["version"] == 1 &&
            old["ownerUserId"] == ownerUserId) old["active"] as? Boolean else null
        if (previous == active) return
        if (previous == null && !active) return
        world.updateGeneralMeta(recorder, general, general.meta + (META_KEY to linkedMapOf(
            "version" to 1, "ownerUserId" to ownerUserId, "active" to active)))
        val kind = if (active) EventKind.OFFLINE_DELEGATION_STARTED else EventKind.OFFLINE_DELEGATION_ENDED
        val recordKind = if (active) RecordKind.OFFLINE_DELEGATION_STARTED else RecordKind.OFFLINE_DELEGATION_ENDED
        world.recordEvent(kind, AudienceTarget.Self(generalId), EventKey.derive(
            kind.code, world.worldId.value.toString(), generalId.toString(), ownerUserId.toString(),
            phase.year.toString(), phase.month.toString(), phase.phase.toString()))
        Records.general(world, generalId, recordKind,
            if (active) "미접속 위임이 시작되었습니다." else "미접속 위임이 종료되었습니다.")
    }

    companion object { private const val META_KEY = "offlineDelegationState" }
}
