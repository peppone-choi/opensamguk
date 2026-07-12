package opensamguk.logic.war

import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.getTechCost
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound

/**
 * BO1 — the OUTER `processWar()` wrapper. Port target = PHP `process_war.php:8-130`.
 *
 * **OWNS the #1-parity-risk RNG creation site** (`:11`): it builds the SINGLE `RandUtil(LiteHashDrbg(warSeed))`
 * ONCE and threads it BY REFERENCE into the attacker + city + every defender candidate. The inner phase
 * machine ([processWarNG], F3) NEVER re-creates the rng; the wrapper is the sole creation site.
 *
 * Responsibilities (the OUTER entry, distinct from the inner `processWarNG`):
 *  1. build the one rng + the [WarUnitGeneral] attacker + the [WarUnitCity] city (`:11-38`);
 *  2. build the defender candidate list ([orderDefenders]) + the LAZY [getNextDefender] closure (`:36-86`)
 *     — the closure applies-DB on the previous defender (here: records its snapshot delta) then yields the
 *     next, terminating on order<=0 or list-exhaustion;
 *  3. delegate to [processWarNG] EXACTLY ONCE (`:88`) — never the inner machine directly from the resolver;
 *  4. apply the post-loop side-effects (`:98-124`) as ChangeRecorder DELTAS (NO inline DB):
 *      - supply-rice decrement (`city.supply && phase>0`, Util::setRound → [phpRound], clamped `max(0,…)`);
 *      - the +1000/+500 capital/non-capital rice bonus (`conquerCity && phase==0`);
 *      - the city.dead 0.4 (attacker-city) / 0.6 (defender-city) split (`%i` sqleval = int-truncated).
 *
 * Returns the [ProcessWarResult] carrying `conquerCity` (→ ConquerCity, B2) + the computed deltas the
 * resolver (BO3) folds into the ChangeRecorder.
 */

/** The non-unit context the wrapper reads off the raw defender nation + the two city ids (`:14-38,:98-124`). */
data class ProcessWarEnv(
    val defenderNationTech: Double,
    val defenderNationRice: Int,
    val defenderNationCapitalCityId: Int,
    val attackerCityId: Int,
    val defenderCityId: Int,
)

/** The wrapper's computed post-loop deltas (recorded by BO3 — never written inline here). */
data class ProcessWarResult(
    val conquerCity: Boolean,
    /** The defender nation's new rice after the supply-decrement OR the +1000/+500 conquest bonus (null = unchanged). */
    val defenderNationRice: Int?,
    /** `city.dead += totalDead*0.4` (int-truncated) on the ATTACKER's city. */
    val attackerCityDeadDelta: Int,
    /** `city.dead += totalDead*0.6` (int-truncated) on the DEFENDER's city. */
    val defenderCityDeadDelta: Int,
    /** The mutated attacker working state (snapshot delta source). */
    val attacker: WarUnitGeneral,
    /** The mutated defender-city working state (snapshot delta source). */
    val city: WarUnitCity,
    val defenders: List<WarUnitGeneral>,
    /** Exposed for the wrapper test's supply-rice oracle (the city train/atmos used in the rice formula). */
    val defenderCityTrainAtmosForTest: Int,
)

/** The inner-machine seam (defaults to the real [processWarNG]); the wrapper test injects a recording fake. */
typealias ProcessWarInner = (
    rng: RandUtil,
    attacker: WarUnitGeneral,
    getNextDefender: (WarUnit?, Boolean) -> WarUnit?,
    city: WarUnitCity,
) -> Boolean

fun processWar(
    warSeed: String,
    attackerGeneral: General,
    attackerNation: Nation,
    defenderCity: City,
    defenderCandidates: List<General>,
    attackerCrewType: GameUnitDetail,
    attackerTech: Int,
    defenderCrewType: GameUnitDetail,
    defenderTech: Int,
    pipeline: GeneralActionPipeline,
    year: Int,
    startYear: Int,
    env: ProcessWarEnv,
    hooks: WarBattleHooks = WarBattleHooks.NOOP,
    attackerCityLevel: Int = 9,
    attackerIsCapital: Boolean = false,
    runInner: ProcessWarInner = { rng, attacker, gnd, city -> processWarNG(rng, attacker, gnd, city, hooks) },
): ProcessWarResult {
    // (1) THE single shared rng — built ONCE here (process_war.php:11), threaded by reference.
    val rng = RandUtil(LiteHashDrbg(warSeed))

    val attacker = WarUnitGeneral(
        rng = rng, state = WarUnitGeneralState(attackerGeneral), pipeline = pipeline,
        crewType = attackerCrewType, tech = attackerTech, isAttacker = true,
        cityLevel = attackerCityLevel, isCapital = attackerIsCapital,
    )

    val city = WarUnitCity(
        rng = rng, state = WarUnitCityState(defenderCity), year = year, startYear = startYear,
    )

    // (2) the defender candidate WarUnits (each on the SHARED rng) + the ordered battle queue.
    val candidateUnits = defenderCandidates.map { g ->
        val crewType = GameUnitConst.byId(
            g.crewTypeId.takeIf { it >= GameUnitConst.CREWTYPE_CASTLE } ?: GameUnitConst.DEFAULT_CREWTYPE,
        ) ?: defenderCrewType
        WarUnitGeneral(
            rng = rng, state = WarUnitGeneralState(g), pipeline = pipeline,
            crewType = crewType, tech = defenderTech, isAttacker = false,
            cityLevel = defenderCity.level, isCapital = false,
        )
    }
    val orderedDefenders = orderDefenders(candidateUnits, attacker, city)

    // the LAZY getNextDefender closure (process_war.php:66-86) — applyDB-on-prev (snapshot here) then yield.
    val iter = orderedDefenders.iterator()
    val getNextDefender: (WarUnit?, Boolean) -> WarUnit? = gnd@{ prev, reqNext ->
        // PHP $prevDefender->applyDB($db) — modeled as snapshot capture (the resolver records the delta).
        prev?.let { /* delta captured via the unit's own state.snapshot() at resolution time */ }
        if (!reqNext) return@gnd null
        if (!iter.hasNext()) return@gnd null
        val next = iter.next()
        if (extractBattleOrder(next, attacker) <= 0) return@gnd null
        next
    }

    // (3) delegate to the inner machine — EXACTLY ONCE.
    val conquerCity = runInner(rng, attacker, getNextDefender, city)

    // (4) post-loop side-effects as DELTAS (process_war.php:98-124).
    var defenderNationRice: Int? = null
    val cta = city.getCityTrainAtmos()
    if (defenderCity.supplyState != 0) {
        if (city.getPhase() > 0) {
            // supply-rice decrement (process_war.php:99-106).
            var rice = city.getKilled() / 100.0 * 0.8
            rice *= city.getCrewType().rice
            rice *= getTechCost(env.defenderNationTech.toInt())
            rice *= cta / 100.0 - 0.2
            val riceInt = phpRound(rice)   // Util::setRound
            defenderNationRice = maxOf(0, env.defenderNationRice - riceInt)
        } else if (conquerCity) {
            // capital/non-capital conquest rice bonus (process_war.php:107-113).
            defenderNationRice = if (env.defenderNationCapitalCityId == env.defenderCityId) {
                env.defenderNationRice + 1000
            } else {
                env.defenderNationRice + 500
            }
        }
    }

    // the city.dead 0.4/0.6 split (process_war.php:116-124) — %i sqleval = int truncation toward zero.
    val totalDead = attacker.getKilled() + attacker.getDead()
    val attackerCityDeadDelta = (totalDead * 0.4).toInt()
    val defenderCityDeadDelta = (totalDead * 0.6).toInt()

    return ProcessWarResult(
        conquerCity = conquerCity,
        defenderNationRice = defenderNationRice,
        attackerCityDeadDelta = attackerCityDeadDelta,
        defenderCityDeadDelta = defenderCityDeadDelta,
        attacker = attacker,
        city = city,
        defenders = candidateUnits,
        defenderCityTrainAtmosForTest = cta,
    )
}
