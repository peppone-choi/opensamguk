package opensamguk.logic.statview

import opensamguk.common.constants.EffectiveGameConst
import opensamguk.logic.domain.WorldEnv

/**
 * THE single shared env-builder (P1 review-edit #7).
 *
 * Both call sites — game-api precheck ([opensamguk.logic ...] PrecheckStateViewFactory, Task E2) and
 * game-engine daemon full-mode (ReservedTurnHandler, Task F3) — build the `ConstraintContext.env`
 * through this ONE helper so the precheck and full-mode env can NEVER drift. There is exactly one
 * implementation; both sites import it.
 *
 * The env map keys (`year`/`startYear`/`develCost`) are the SAME keys read by
 * `CommerceInvestment.envOf` (so cost/front-debuff math reads identical values in both modes).
 * `develCost` is derived per-turn via [EffectiveGameConst.develcost] (NOT cached — PHP recomputes it
 * each turn at func_gamerule.php:219).
 */
object WorldEnvBuilder {

    /** Build the typed [WorldEnv] from (year, startYear); `develCost = (year - startYear + 10) * 2`. */
    fun worldEnv(year: Int, startYear: Int): WorldEnv =
        WorldEnv(
            year = year,
            startYear = startYear,
            develCost = EffectiveGameConst.develcost(year, startYear),
        )

    /**
     * Build the `ConstraintContext.env` map from (year, startYear). Keys are insertion-ordered
     * (`year`, `startYear`, `develCost`) and read as `Number` by `CommerceInvestment.envOf`.
     *
     * This is the P2 precheck/command env — it carries NO `month`/`currentEventID` (the monthly tick
     * uses the widened [envMap] overload below). Keeping it byte-identical guarantees precheck can
     * never drift from the per-command full-mode env.
     */
    fun envMap(year: Int, startYear: Int): Map<String, Any?> {
        val e = worldEnv(year, startYear)
        return linkedMapOf(
            "year" to e.year,
            "startYear" to e.startYear,
            "develCost" to e.develCost,
        )
    }

    /**
     * FC2 — the widened monthly-tick env: the P2 keys (`year`, `startYear`, `develCost`) PLUS `month`
     * (PHP event-env key `$env['month']`, read by Date/DateRelative conditions + the
     * Income/RandomizeCityTradeRate/UpdateNationLevel/RaiseDisaster Actions) and `currentEventID` (PHP
     * `$env['currentEventID']`, injected per event ROW by the dispatcher and read by
     * DeleteEvent/AutoDeleteInvader). Keys stay insertion-ordered; `currentEventID` is always present
     * (so it is "settable per dispatch") and defaults `null` outside a dispatch.
     *
     * This is the SAME builder, NOT a forked parallel env — the tick simply asks for more keys.
     */
    fun envMap(year: Int, startYear: Int, month: Int, currentEventID: Int? = null): Map<String, Any?> {
        val e = worldEnv(year, startYear)
        return linkedMapOf(
            "year" to e.year,
            "startYear" to e.startYear,
            "develCost" to e.develCost,
            "month" to month,
            "currentEventID" to currentEventID,
        )
    }

    /** Convenience: derive the env map directly from a [WorldEnv] (same single source of `develCost`). */
    fun envMap(env: WorldEnv): Map<String, Any?> = envMap(env.year, env.startYear)
}
