package opensamguk.gameapi.court.office

import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.input.Phase
import opensamguk.logic.office.OfficeTenureCodec
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** Read the persisted tenure store of the process world; no ownership, appointment or lifecycle inference. */
@Component
class LocalOfficesReader(
    private val worlds: WorldStateReadRepository,
    private val gameKv: GameKvReadRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(worldId: Int, nationId: Int): LocalOfficesDto {
        val world = worlds.findProcessWorld() ?: return LocalOfficesProjection.unavailable(LocalOfficesReason.WORLD_UNAVAILABLE)
        if (world.id <= 0 || worldId != world.id) return LocalOfficesProjection.unavailable(LocalOfficesReason.WORLD_UNAVAILABLE)
        val now = try {
            require(world.currentYear > 0)
            Phase(world.currentYear, world.currentMonth, world.currentPhase)
        } catch (_: IllegalArgumentException) {
            return LocalOfficesProjection.unavailable(LocalOfficesReason.WORLD_UNAVAILABLE)
        }
        // A free general (no nation) has no local office question at all — answered before any store read.
        if (nationId <= 0) return LocalOfficesProjection.unavailable(LocalOfficesReason.NO_NATION, now)
        val row = gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", OfficeTenureCodec.META_KEY)
            ?: return LocalOfficesProjection.notSeeded(now)
        if (row.worldId != world.id) return LocalOfficesProjection.unavailable(LocalOfficesReason.TENURES_INVALID, now)
        val tenures = try {
            OfficeTenureCodec.decode(row.value)
        } catch (_: IllegalArgumentException) {
            return LocalOfficesProjection.unavailable(LocalOfficesReason.TENURES_INVALID, now)
        } catch (_: IllegalStateException) {
            return LocalOfficesProjection.unavailable(LocalOfficesReason.TENURES_INVALID, now)
        } catch (_: ArithmeticException) {
            return LocalOfficesProjection.unavailable(LocalOfficesReason.TENURES_INVALID, now)
        }
        return LocalOfficesProjection.project(now, nationId, tenures)
    }
}
