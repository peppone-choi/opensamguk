package opensamguk.gameapi.court.reinforcement

import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.input.Phase
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class ReinforcementRequestsSnapshot(
    val status: ReinforcementRequestsStatus,
    val reason: String,
    val now: Phase?,
)

/**
 * No request writer exists yet: `VassalState` persists contracts and tribute receipts only, and `ReinforcementRequest`
 * has no store or operation source. So a readable world answers NOT_SEEDED; nothing is synthesized from contracts.
 */
@Component
class ReinforcementRequestsReader(private val worlds: WorldStateReadRepository) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(worldId: Int): ReinforcementRequestsSnapshot {
        val world = worlds.findProcessWorld() ?: return unavailable(ReinforcementRequestsReason.WORLD_UNAVAILABLE)
        if (world.id <= 0 || worldId != world.id) return unavailable(ReinforcementRequestsReason.WORLD_UNAVAILABLE)
        val now = try {
            require(world.currentYear > 0)
            Phase(world.currentYear, world.currentMonth, world.currentPhase)
        } catch (_: IllegalArgumentException) {
            return unavailable(ReinforcementRequestsReason.WORLD_DATE_INVALID)
        }
        return ReinforcementRequestsSnapshot(ReinforcementRequestsStatus.NOT_SEEDED,
            ReinforcementRequestsReason.REQUEST_SOURCE_ABSENT, now)
    }

    private fun unavailable(reason: String) =
        ReinforcementRequestsSnapshot(ReinforcementRequestsStatus.UNAVAILABLE, reason, null)
}
