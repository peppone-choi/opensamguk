package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.statview.MemoryStateView
import opensamguk.logic.statview.WorldEnvBuilder
import org.springframework.stereotype.Component

/**
 * Task E2 — loads the last-flushed `general`/`city`/`nation`/`world_state` rows through the precheck
 * JPA READ repos (E1), materializes them into the shared `:logic` entities, and assembles the
 * precheck-mode [MemoryStateView] + the `ConstraintContext.env` map.
 *
 * **The env map is built through the ONE shared [WorldEnvBuilder]** — the SAME helper the daemon's
 * `ReservedTurnHandler` uses in full mode (Task F3). Both call sites import it, so the precheck and
 * full-mode env (`year`/`startYear`/`develCost`) can never drift. `year = world_state.current_year`;
 * `startYear` lives in the scenario `config` jsonb bag (`config["startYear"]`); `develCost` is then
 * derived per-turn inside the builder (`EffectiveGameConst.develcost`).
 *
 * Only the actor general's own city/nation are loaded into the view — the precheck slice evaluates
 * the SAME constraint set the daemon does, and those constraints reference only the actor + its
 * city/nation (OccupiedCity/SuppliedCity/RemainCityCapacity/NotWanderingNation/ReqGeneralGold).
 */
@Component
class PrecheckStateViewFactory(
    private val generals: GeneralReadRepository,
    private val cities: CityReadRepository,
    private val nations: NationReadRepository,
    private val worldStates: WorldStateReadRepository,
) {

    /**
     * The materialized precheck inputs for one actor: the actor's [General] (the service derives
     * `cityId`/`nationId` from it), the assembled [MemoryStateView], and the shared env map.
     */
    data class PrecheckState(
        val actor: General,
        val view: MemoryStateView,
        val env: Map<String, Any?>,
    )

    /** Load + assemble the precheck state for [generalId], or `null` when the general row is absent. */
    fun build(generalId: Int): PrecheckState? {
        val actor: General = generals.findById(generalId).orElse(null)?.toLogic() ?: return null

        val cityById = LinkedHashMap<Int, City>()
        cities.findById(actor.cityId).orElse(null)?.toLogic()?.let { cityById[it.id] = it }

        val nationById = LinkedHashMap<Int, Nation>()
        nations.findById(actor.nationId).orElse(null)?.toLogic()?.let { nationById[it.id] = it }

        val env = envMap()
        val view = MemoryStateView(
            generals = linkedMapOf(actor.id to actor),
            cities = cityById,
            nations = nationById,
            env = env,
        )
        return PrecheckState(actor = actor, view = view, env = env)
    }

    /** Build the `ConstraintContext.env` map from the singleton `world_state` via the shared builder. */
    private fun envMap(): Map<String, Any?> {
        val ws = worldStates.findAll().firstOrNull()
            ?: error("world_state singleton row is missing")
        val year = ws.currentYear
        val startYear = (ws.config["startYear"] as? Number)?.toInt()
            ?: error("world_state.config.startYear is missing")
        return WorldEnvBuilder.envMap(year, startYear)
    }
}
