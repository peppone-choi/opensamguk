package opensamguk.engine.turn

import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.City as LogicCity
import opensamguk.logic.domain.General as LogicGeneral
import opensamguk.logic.domain.Nation as LogicNation

/**
 * Copy-on-write view over [InMemoryTurnWorld] for ONE general's turn (P1 Task F1).
 *
 * Reads fall through to the world; writes stage into the overlay until applied. This is the
 * structural analogue of the TS `WorldStateView.overrides` (the per-general copy-on-write
 * override that takes precedence over the shared world view) in
 * `app/game-engine/src/turn/ai/generalAi/worldStateView.ts`.
 *
 * The overlay stages ENGINE-model rows ([TurnGeneral]/[City]) so the apply path mutates the
 * world in its own model (the resolver/ChangeRecorder operate on the LOGIC model — F2/F3 — and
 * the conversion lives here). The overlay never mutates the world: a staged write is visible via
 * [getGeneral]/[getCity] but the world's rows are untouched until [applyTo] runs.
 *
 * Engine `TurnGeneral`/`City` carry more columns than the logic `General`/`City`. Conversion maps
 * the slice-relevant subset and carries `meta` verbatim (insertion order preserved):
 *  - `General.intel`        <- `TurnGeneral.stats.intelligence`
 *  - `General.experience`   <- `TurnGeneral.experience` (Int -> raw Double accumulator)
 *  - `General.dedication`   <- `TurnGeneral.dedication` (Int -> raw Double accumulator)
 *  - `City.trust`           <- `City.meta["trust"]` (engine City has no trust column; the logic
 *                              che math reads `trust/100.0` & `trust/80.0` as a Double)
 */
class PerTurnOverlay(private val world: InMemoryTurnWorld) {

    private val stagedGenerals = LinkedHashMap<Int, TurnGeneral>()
    private val stagedCities = LinkedHashMap<Int, City>()

    // --- engine-model reads (overlay-first, fall through to world) ---

    fun getGeneral(id: Int): TurnGeneral? = stagedGenerals[id] ?: world.getGeneralById(id)
    fun getCity(id: Int): City? = stagedCities[id] ?: world.getCityById(id)
    fun getNation(id: Int): Nation? = world.getNationById(id)

    // --- staged writes (visible in the overlay; world untouched until applyTo) ---

    fun stageGeneral(next: TurnGeneral) { stagedGenerals[next.id] = next }
    fun stageCity(next: City) { stagedCities[next.id] = next }

    fun isGeneralStaged(id: Int): Boolean = stagedGenerals.containsKey(id)
    fun isCityStaged(id: Int): Boolean = stagedCities.containsKey(id)

    /** Snapshot of the currently staged engine rows (read-only). */
    fun stagedGeneralIds(): Set<Int> = stagedGenerals.keys.toSet()
    fun stagedCityIds(): Set<Int> = stagedCities.keys.toSet()

    // --- logic-model reads (overlay-first; conversion engine -> logic) ---

    fun getLogicGeneral(id: Int): LogicGeneral? = getGeneral(id)?.let { toLogicGeneral(it) }
    fun getLogicCity(id: Int): LogicCity? = getCity(id)?.let { toLogicCity(it) }
    fun getLogicNation(id: Int): LogicNation? = getNation(id)?.let { toLogicNation(it) }

    companion object {
        /** Engine [TurnGeneral] -> logic [General] (slice subset + meta verbatim). */
        fun toLogicGeneral(g: TurnGeneral): LogicGeneral = LogicGeneral(
            id = g.id,
            nationId = g.nationId,
            cityId = g.cityId,
            leadership = g.stats.leadership,
            strength = g.stats.strength,
            intel = g.stats.intelligence,
            injury = g.injury,
            experience = g.experience.toDouble(),
            dedication = g.dedication.toDouble(),
            officerLevel = g.officerLevel,
            gold = g.gold,
            rice = g.rice,
            meta = g.meta,
        )

        /** Engine [City] -> logic [City] (slice subset; `trust` from `meta["trust"]`). */
        fun toLogicCity(c: City): LogicCity = LogicCity(
            id = c.id,
            nationId = c.nationId,
            level = c.level,
            commerce = c.commerce,
            commerceMax = c.commerceMax,
            agriculture = c.agriculture,
            agricultureMax = c.agricultureMax,
            supplyState = c.supplyState,
            frontState = c.frontState,
            trust = metaDouble(c.meta, "trust"),
            meta = c.meta,
        )

        /** Engine [Nation] -> logic [Nation] (slice subset). */
        fun toLogicNation(n: Nation): LogicNation = LogicNation(
            id = n.id,
            level = n.level,
            capitalCityId = n.capitalCityId,
        )
    }
}
