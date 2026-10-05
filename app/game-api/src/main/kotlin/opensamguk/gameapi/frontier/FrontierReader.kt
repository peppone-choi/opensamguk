package opensamguk.gameapi.frontier

import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.input.Phase
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** Confirm the process world and its clock; there is no contact store to read yet (see FrontierProjection). */
@Component
class FrontierReader(private val worlds: WorldStateReadRepository) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(worldId: Int, nationId: Int): FrontierDto {
        val world = worlds.findProcessWorld() ?: return FrontierProjection.unavailable(FrontierReason.WORLD_UNAVAILABLE)
        if (world.id <= 0 || worldId != world.id) return FrontierProjection.unavailable(FrontierReason.WORLD_UNAVAILABLE)
        val now = try {
            require(world.currentYear > 0)
            Phase(world.currentYear, world.currentMonth, world.currentPhase)
        } catch (_: IllegalArgumentException) {
            return FrontierProjection.unavailable(FrontierReason.WORLD_UNAVAILABLE)
        }
        return FrontierProjection.project(now, nationId)
    }
}
