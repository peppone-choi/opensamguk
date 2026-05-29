package opensamguk.logic.world

import opensamguk.logic.domain.City
import opensamguk.logic.domestic.calcCityWarGoldIncome
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound

/**
 * P3 / AREA A1 / Task IN3 — `ProcessWarIncome` Action (PreMonth).
 *
 * Faithful port of PHP `Event/Action/ProcessWarIncome.php:11-36`:
 *   - group cities by nation; per nation in `getAllNationStaticInfo()` (ASCENDING id):
 *       - SKIP `level <= 0` (wandering forces — no gold add);
 *       - `income = getWarGoldIncome(type, cities)` = Σ `calcCityWarGoldIncome(city, nationTypeObj)`
 *         (the IN1 per-city term, [calcCityWarGoldIncome]); `nation.gold += income`.
 *   - THEN ONE bulk city update across ALL cities — the PHP filter is `true` (no WHERE), so
 *     `pop += dead * 0.2, dead = 0` applies to EVERY city INCL. unowned/neutral (부상병 회복).
 *
 * `EventTarget = PRE_MONTH` (L5, JUST BEFORE preUpdateMonthly) — it reads the post-supply snapshot
 * (UpdateCitySupply ran at array index 0 of the F2 `pre_month` row; ProcessWarIncome is index 1, both
 * priority 9000 — the ordering is the array index, NOT a priority/id tiebreak).
 *
 * `pop + dead*0.2` produces a float (int pop column on store → MariaDB rounds half-away); modelled with
 * [phpRound]. The income fold runs over the NATION-TYPE source ONLY ([GeneralActionPipeline.nationIncomeFold]).
 */

/** One nation's war-income input (PHP `getAllNationStaticInfo()` row: id + level + gold + type). */
data class WarIncomeNation(
    val id: Int,
    val level: Int,
    val gold: Int,
    val nationType: GeneralActionModule?,
)

/** Per-nation war-gold add: the summed [income] + the resulting [newGold]. */
data class WarIncomeNationAdd(
    val nationId: Int,
    val income: Int,
    val newGold: Int,
)

/** Per-city 부상병 회복 update: `pop += dead*0.2`, `dead = 0` (ALL cities, filter `true`). */
data class WarIncomeCityUpdate(
    val cityId: Int,
    val newPop: Int,
    val newDead: Int,
)

/** The structured result the [EventAction] wrapper applies to the world + flush. */
data class ProcessWarIncomeResult(
    val nationGoldAdds: List<WarIncomeNationAdd>,
    val cityUpdates: List<WarIncomeCityUpdate>,
)

/**
 * The pure IN3 core: per-nation war-gold (level>0 only, ascending id) + the ALL-cities 부상병 update.
 */
fun processWarIncome(
    nations: List<WarIncomeNation>,
    cities: List<City>,
    pipeline: GeneralActionPipeline,
): ProcessWarIncomeResult {
    val citiesByNation = cities.groupBy { it.nationId }

    val nationGoldAdds = ArrayList<WarIncomeNationAdd>()
    for (nation in nations.sortedBy { it.id }) {
        if (nation.level <= 0) continue // wandering forces — skipped
        val nationCities = citiesByNation[nation.id] ?: emptyList()
        var income = 0
        for (c in nationCities) {
            income += calcCityWarGoldIncome(c, nation.nationType, pipeline)
        }
        nationGoldAdds.add(WarIncomeNationAdd(nation.id, income, nation.gold + income))
    }

    // The bulk update: filter `true` → EVERY city (incl. unowned/neutral). Deterministic ascending id.
    val cityUpdates = cities.sortedBy { it.id }.map { c ->
        WarIncomeCityUpdate(c.id, phpRound(c.population + c.dead * 0.2), 0)
    }

    return ProcessWarIncomeResult(nationGoldAdds, cityUpdates)
}

/**
 * The F2-dispatched `ProcessWarIncome` leaf (registered by name; F2's `pre_month/9000` row names it at
 * ARRAY INDEX 1, after `UpdateCitySupply` at index 0 — the family does NOT author the row or its order).
 * Takes NO args. Reads the world snapshot + pipeline from a richer [ProcessWarIncomeContext].
 */
class ProcessWarIncomeAction : EventAction {
    override fun run(ctx: EventActionContext) {
        val pwc = ctx as? ProcessWarIncomeContext
            ?: error("ProcessWarIncomeAction requires a ProcessWarIncomeContext")
        pwc.applyWarIncome(processWarIncome(pwc.nations(), pwc.cities(), pwc.pipeline))
    }

    companion object {
        const val NAME = "ProcessWarIncome"

        /** Register the leaf into the F2-owned factory by name (plan §append protocol; no args). */
        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { ProcessWarIncomeAction() }
    }
}

/** The richer dispatch context [ProcessWarIncomeAction] consumes (supplied by the daemon, not F2). */
interface ProcessWarIncomeContext : EventActionContext {
    val pipeline: GeneralActionPipeline
    fun nations(): List<WarIncomeNation>
    fun cities(): List<City>
    fun applyWarIncome(result: ProcessWarIncomeResult)
}
