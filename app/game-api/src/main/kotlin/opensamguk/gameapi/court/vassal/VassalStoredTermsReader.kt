package opensamguk.gameapi.court.vassal

import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.input.Phase
import opensamguk.logic.vassal.VassalStateCodec
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** Read persisted conditions; no ownership lookup, command eligibility or lifecycle inference. */
@Component
class VassalStoredTermsReader(
    private val worlds: WorldStateReadRepository,
    private val gameKv: GameKvReadRepository,
    private val generals: GeneralReadRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(worldId: Int, nationId: Int): StoredVassalTermsSnapshot {
        val world = worlds.findProcessWorld() ?: return unavailable()
        if (world.id <= 0 || worldId != world.id || nationId <= 0) return unavailable()
        val now = try {
            require(world.currentYear > 0)
            Phase(world.currentYear, world.currentMonth, world.currentPhase)
        } catch (_: IllegalArgumentException) {
            return unavailable()
        }
        val row = gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", VassalStateCodec.META_KEY)
            ?: return StoredVassalTermsSnapshot(StoredVassalTermsStatus.NOT_SEEDED, now)
        if (row.worldId != world.id) return unavailable()
        val state = try {
            VassalStateCodec.decode(row.value)
        } catch (_: IllegalArgumentException) {
            return unavailable()
        } catch (_: IllegalStateException) {
            return unavailable()
        }
        val names = state.contracts.asSequence().filter { it.nationId == nationId }
            .map { it.vassalLordId }.distinct().mapNotNull { id ->
                generals.findById(id).orElse(null)?.takeIf {
                    it.worldId == world.id && it.nationId == nationId && it.name.isNotBlank()
                }?.let { id to it.name }
            }.toMap()
        return StoredVassalTermsSnapshot(StoredVassalTermsStatus.READY, now,
            VassalStoredTermsView.project(state, nationId, names))
    }

    private fun unavailable() = StoredVassalTermsSnapshot(StoredVassalTermsStatus.UNAVAILABLE)
}
