package opensamguk.engine.run

import java.time.Duration
import java.time.Instant
import opensamguk.common.turn.CatchUpSnapshot
import opensamguk.common.turn.TurnCatchUp

/** Decides operational pacing; the caller commits each transition with the daemon's flush path. */
internal class TurnCatchUpCoordinator(
    private val nextWorldRun: () -> Instant,
    private val tickSeconds: () -> Int,
    private val current: () -> TurnCatchUp?,
    private val persist: (plan: TurnCatchUp, completed: Boolean) -> Unit,
) {
    fun plan(): TurnCatchUp? = current()?.takeIf { it.active }

    fun snapshot(at: Instant): CatchUpSnapshot =
        current()?.snapshot(nextWorldRun(), at) ?: CatchUpSnapshot(false, 2, 0, 0, null)

    fun ensure(at: Instant, reanchor: Boolean) {
        val existing = plan()
        if (existing != null) {
            if (reanchor) {
                // A short restart preserves the remaining wait. A long outage cannot unleash all
                // overdue boundaries in one burst: at most the first pending event is due now.
                val virtualAt = minOf(existing.virtualTime(at), nextWorldRun())
                persist(existing.copy(anchorAt = at, anchorGameAt = virtualAt, lastCalculatedAt = at), false)
            }
            return
        }
        val next = nextWorldRun()
        if (TurnCatchUp.shouldStart(next, tickSeconds(), at)) {
            persist(TurnCatchUp.start(next, at), false)
        }
    }

    fun refresh(at: Instant) {
        val existing = plan() ?: return
        if (Duration.between(existing.lastCalculatedAt, at) >= Duration.ofMinutes(1)) {
            persist(existing.copy(lastCalculatedAt = at), false)
        }
    }

    fun finishIfCurrent(at: Instant) {
        val existing = plan() ?: return
        if (!nextWorldRun().isBefore(at)) {
            persist(existing.copy(active = false, lastCalculatedAt = at), true)
        }
    }

    fun switchMultiplier(multiplier: Int, at: Instant): CatchUpSnapshot {
        require(multiplier == 2 || multiplier == 4) { "catch-up multiplier must be 2 or 4" }
        val existing = checkNotNull(plan()) { "catch-up is not active" }
        val changed = existing.switchMultiplier(multiplier, at)
        if (changed != existing) persist(changed, false)
        return changed.snapshot(nextWorldRun(), at)
    }
}
