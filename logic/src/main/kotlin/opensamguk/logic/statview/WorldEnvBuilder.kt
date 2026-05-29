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
     */
    fun envMap(year: Int, startYear: Int): Map<String, Any?> {
        val e = worldEnv(year, startYear)
        return linkedMapOf(
            "year" to e.year,
            "startYear" to e.startYear,
            "develCost" to e.develCost,
        )
    }

    /** Convenience: derive the env map directly from a [WorldEnv] (same single source of `develCost`). */
    fun envMap(env: WorldEnv): Map<String, Any?> = envMap(env.year, env.startYear)
}
