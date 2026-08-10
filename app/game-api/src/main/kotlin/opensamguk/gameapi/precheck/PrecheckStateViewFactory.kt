package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.DiplomacyReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.actions.military.UnitSetTable
import opensamguk.logic.world.CityConstRegistry
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
     * The view materializes the actor's current city/nation. The env also carries every city level owned
     * by that nation because recruit constraints evaluate nation-wide unit unlocks in both precheck and
     * daemon full mode.
 */
@Component
class PrecheckStateViewFactory(
    private val generals: GeneralReadRepository,
    private val cities: CityReadRepository,
    private val nations: NationReadRepository,
    private val diplomacies: DiplomacyReadRepository,
    private val worldStates: WorldStateReadRepository,
) {

    /**
     * The materialized precheck inputs for one actor: the actor's [General] (the service derives
     * `cityId`/`nationId` from it), the assembled [MemoryStateView], the shared env map, and the
     * actor nation's directional [Diplomacy] rows (consumed by the dest-* constraint family once
     * C-DEST lands; loaded here so the precheck slice already has the full diplomacy view).
     */
    data class PrecheckState(
        val actor: General,
        val view: MemoryStateView,
        val env: Map<String, Any?>,
        val diplomacy: List<Diplomacy>,
    )

    /** Load + assemble the precheck state for [generalId], or `null` when the general row is absent. */
    fun build(generalId: Int): PrecheckState? {
        val actor: General = generals.findById(generalId).orElse(null)?.toLogic() ?: return null

        val cityById = LinkedHashMap<Int, City>()
        cities.findById(actor.cityId).orElse(null)?.toLogic()?.let { cityById[it.id] = it }
        val ownCities = cities.findByNationIdOrderByIdAsc(actor.nationId).map { it.toLogic() }

        val nationById = LinkedHashMap<Int, Nation>()
        nations.findById(actor.nationId).orElse(null)?.toLogic()?.let { nationById[it.id] = it }

        val diplomacy: List<Diplomacy> =
            diplomacies.findBySrcNationId(actor.nationId).map { it.toLogic() }

        val env = LinkedHashMap(envMap()).apply {
            this["ownCities"] = ownCities.associateTo(LinkedHashMap()) { it.id to it.level }
        }
        // CD1 — preload the actor nation's directional diplomacy rows into the view so the dest-*
        // constraint family (AllowDiplomacyStatus / battleground at-war existence) resolves
        // RequirementKey.Diplomacy WITHOUT touching the DB inside test().
        val view = MemoryStateView(
            generals = linkedMapOf(actor.id to actor),
            cities = cityById,
            nations = nationById,
            env = env,
            diplomacy = diplomacy,
        )
        return PrecheckState(actor = actor, view = view, env = env, diplomacy = diplomacy)
    }

    /** Build the `ConstraintContext.env` map from the singleton `world_state` via the shared builder. */
    private fun envMap(): Map<String, Any?> {
        val ws = worldStates.findAll().firstOrNull()
            ?: error("world_state singleton row is missing")
        val year = ws.currentYear
        val startYear = numberOf(ws.config["startYear"])
            ?: numberOf(ws.config["startyear"])
            ?: ws.startYear
            ?: numberOf(ws.meta["startYear"])
            ?: error("world_state.config.startYear is missing")
        return LinkedHashMap(WorldEnvBuilder.commandEnvMap(
            year = year,
            startYear = startYear,
            month = ws.currentMonth,
            phase = ws.currentPhase,
        )).apply {
            this["unitSet"] = UnitSetTable.activeUnitSet(ws.config, ws.meta)
            this["mapName"] = CityConstRegistry.activeMapName(ws.config, ws.meta)
        }
    }

    private fun numberOf(value: Any?): Int? = (value as? Number)?.toInt()
}
