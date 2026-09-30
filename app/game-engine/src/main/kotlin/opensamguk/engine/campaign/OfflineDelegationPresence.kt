package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.RuleProfile

/** Records a daemon-validated owner pulse on the authoritative general row. */
internal class OfflineDelegationPresence(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) {
    enum class Result { RECORDED, UNCHANGED, REJECTED }

    fun record(generalId: Int, ownerUserId: Int): Result {
        if (world.ruleProfile != RuleProfile.HWIHA || generalId <= 0 || ownerUserId <= 0) return Result.REJECTED
        val general = world.getGeneralById(generalId) ?: return Result.REJECTED
        if (general.userId?.toIntOrNull() != ownerUserId) return Result.REJECTED
        val state = world.getState()
        val now = runCatching { DelegationPhase(state.currentYear, state.currentMonth, state.currentPhase) }
            .getOrNull() ?: return Result.REJECTED
        val stored = OfflineDelegationLease.read(general.meta)
        if (stored != null && stored.worldId == world.worldId.value && stored.generalId == generalId &&
            stored.ownerUserId == ownerUserId &&
            stored.lastActive.ordinal > now.ordinal) return Result.REJECTED
        val next = if (stored?.worldId == world.worldId.value && stored.generalId == generalId &&
            stored.ownerUserId == ownerUserId) {
            stored.refreshedAt(now)
        } else OfflineDelegationLease(world.worldId.value, generalId, ownerUserId, now)
        if (next == stored) return Result.UNCHANGED
        world.updateGeneralMeta(recorder, general, general.meta + (OfflineDelegationLease.META_KEY to next.toMetaValue()))
        return Result.RECORDED
    }
}
