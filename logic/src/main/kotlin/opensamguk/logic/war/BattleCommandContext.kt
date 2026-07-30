package opensamguk.logic.war

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * BO2 — the battle-command carrier the `che_출병` resolver (BO3) reads. Mirrors the core2026
 * `actionContextBuilder` battle slice: it stages the world data the resolver needs AFTER the per-turn
 * choice draw (the resolver itself owns the single `rng.choice` + the warSeed build + the processWar call).
 *
 * The engine [opensamguk.engine.war] builder populates this from the InMemoryTurnWorld; the logic module
 * stays pure (plain data + lookups, no engine types). The PHP DB reads it stands in for:
 *  - `searchDistanceListToDest(...)` → [distanceList] (`che_출병.php:161`);
 *  - `SELECT no FROM general WHERE nation=city.nation AND city=city.id AND nation!=0` →
 *    [defenderGeneralsByCity] (`process_war.php:40-41`);
 *  - the raw defender city / nation rows → [cityById] / [nationById] (`process_war.php:30-38`);
 *  - the war-seed lineage inputs → [hiddenSeed] / [loggerYear] / [loggerMonth] (`che_출병.php:245-253`).
 */
data class BattleCommandContext(
    /** The attacker general's current city (`che_출병.php:148`). */
    val attackerCityId: Int,
    /** The originally-requested target city (`finalTargetCityID`, `che_출병.php:151`). */
    val finalTargetCityId: Int,
    /** The attacker's nation id (`che_출병.php:144`). */
    val myNationId: Int,
    /**
     * The dist-bucketed BFS result over the allowed path graph ([searchDistanceListToDest]) — `dist →
     * [(cityId, nationId), …]` (`che_출병.php:161`). The resolver derives candidateCities + the choice draw.
     */
    // (allowed graph = my-state-0 AT-WAR nations ∪ {me} ∪ {0 neutral}; devsam state 0 = 교전중)
    val distanceList: Map<Int, List<Pair<Int, Int>>>,
    /** city id → that city's defender generals (same-nation, non-neutral) (`process_war.php:40-41`). */
    val defenderGeneralsByCity: Map<Int, List<General>>,
    /** city id → the raw [City] row (the chosen defender city, `process_war.php:38`). */
    val cityById: Map<Int, City>,
    /** nation id → the raw [Nation] row (attacker + defender nation, `process_war.php:30`). */
    val nationById: Map<Int, Nation>,
    val pipelinesByGeneralId: Map<Int, GeneralActionPipeline> = emptyMap(),
    val effectiveGeneralCountByNationId: Map<Int, Int> = emptyMap(),
    /** The install-scoped hidden seed (`UniqueConst::$hiddenSeed`, war-seed component 0). */
    val hiddenSeed: String,
    /** The actor logger's year (war-seed component 2, `che_출병.php:248`). */
    val loggerYear: Int,
    /** The actor logger's month (war-seed component 3, `che_출병.php:249`). */
    val loggerMonth: Int,
)
