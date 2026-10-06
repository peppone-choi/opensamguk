package opensamguk.gameapi.adviser

import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.input.Phase
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** Confirm the process world and its clock; there is no proposal store to read yet (see AdviserProposalsProjection). */
@Component
class AdviserProposalsReader(private val worlds: WorldStateReadRepository) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(worldId: Int): AdviserProposalsDto {
        val world = worlds.findProcessWorld()
            ?: return AdviserProposalsProjection.unavailable(AdviserProposalsReason.WORLD_UNAVAILABLE)
        if (world.id <= 0 || worldId != world.id) return AdviserProposalsProjection.unavailable(AdviserProposalsReason.WORLD_UNAVAILABLE)
        val now = try {
            require(world.currentYear > 0)
            Phase(world.currentYear, world.currentMonth, world.currentPhase)
        } catch (_: IllegalArgumentException) {
            return AdviserProposalsProjection.unavailable(AdviserProposalsReason.WORLD_UNAVAILABLE)
        }
        return AdviserProposalsProjection.project(now)
    }
}
