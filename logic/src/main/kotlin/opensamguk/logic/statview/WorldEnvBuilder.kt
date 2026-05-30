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

    /**
     * FS2 (F-SEED) — the AI env SUPERSET (research §9). `GeneralAI` reads `$env = $gameStor->getAll(true)`
     * and pulls the PHP snake_case keys `develcost`/`year`/`month`/`startyear`/`turnterm`/`init_year`/
     * `init_month`/`autorun_user`/`npc_nation_policy`/`npc_general_policy`. This is its OWN map, DISTINCT
     * from the precheck/full-mode [envMap] (which uses camelCase `year`/`startYear`/`develCost`) — the two
     * env shapes never collide, so the existing precheck/full-mode env CANNOT drift.
     *
     * `develcost` is the same single source as everywhere ([EffectiveGameConst.develcost], func_gamerule.php:219).
     * `autorun_user`/`npc_nation_policy`/`npc_general_policy` are passed through unchanged (the AI ctor reads
     * `$env['autorun_user']['options'] ?? null` and the per-policy KV at `GeneralAI.php:123-124`). Keys are
     * insertion-ordered, matching the order the AI source first reads them.
     */
    fun aiEnvMap(
        year: Int,
        month: Int,
        startYear: Int,
        turnterm: Int,
        initYear: Int,
        initMonth: Int,
        autorunUser: Any? = null,
        npcNationPolicy: Any? = null,
        npcGeneralPolicy: Any? = null,
    ): Map<String, Any?> =
        linkedMapOf(
            "develcost" to EffectiveGameConst.develcost(year, startYear),
            "year" to year,
            "month" to month,
            "startyear" to startYear,
            "turnterm" to turnterm,
            "init_year" to initYear,
            "init_month" to initMonth,
            "autorun_user" to autorunUser,
            "npc_nation_policy" to npcNationPolicy,
            "npc_general_policy" to npcGeneralPolicy,
        )
}
