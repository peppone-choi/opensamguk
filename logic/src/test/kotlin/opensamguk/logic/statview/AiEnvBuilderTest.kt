package opensamguk.logic.statview

import opensamguk.common.constants.EffectiveGameConst
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FS2 — the [WorldEnvBuilder] AI env superset.
 *
 * Port target = PHP `GeneralAI.php` `$this->env` reads — the AI ctor pulls `$env = $gameStor->getAll(true)`
 * and reads the PHP snake_case keys `develcost`/`year`/`month`/`startyear`/`turnterm`/`init_year`/`init_month`/
 * `autorun_user`/`npc_nation_policy`/`npc_general_policy` (research §9). These are DISTINCT from the
 * precheck/full-mode `envMap` keys (camelCase `year`/`startYear`/`develCost`) — the AI env is its own
 * superset. The existing `envMap(year,startYear)` and `envMap(year,startYear,month,currentEventID)` keys
 * MUST stay byte-identical so precheck/full-mode never drift.
 */
class AiEnvBuilderTest {

    @Test
    fun `aiEnvMap carries the full AI superset key set in PHP insertion order`() {
        val env = WorldEnvBuilder.aiEnvMap(
            year = 200,
            month = 3,
            startYear = 184,
            turnterm = 120,
            initYear = 184,
            initMonth = 1,
            autorunUser = null,
            npcNationPolicy = null,
            npcGeneralPolicy = null,
        )
        assertEquals(
            listOf(
                "develcost",
                "year",
                "month",
                "startyear",
                "turnterm",
                "init_year",
                "init_month",
                "autorun_user",
                "npc_nation_policy",
                "npc_general_policy",
            ),
            env.keys.toList(),
        )
    }

    @Test
    fun `aiEnvMap derives develcost via EffectiveGameConst and uses PHP snake_case names`() {
        val env = WorldEnvBuilder.aiEnvMap(
            year = 200,
            month = 3,
            startYear = 184,
            turnterm = 120,
            initYear = 184,
            initMonth = 1,
            autorunUser = null,
            npcNationPolicy = null,
            npcGeneralPolicy = null,
        )
        // develcost = (200 - 184 + 10) * 2 = 52, under the PHP key name "develcost"
        assertEquals(EffectiveGameConst.develcost(200, 184), (env["develcost"] as Number).toInt())
        assertEquals(52, (env["develcost"] as Number).toInt())
        assertEquals(200, (env["year"] as Number).toInt())
        assertEquals(3, (env["month"] as Number).toInt())
        assertEquals(184, (env["startyear"] as Number).toInt())
        assertEquals(120, (env["turnterm"] as Number).toInt())
        assertEquals(184, (env["init_year"] as Number).toInt())
        assertEquals(1, (env["init_month"] as Number).toInt())
    }

    @Test
    fun `aiEnvMap passes through the policy KV and autorun_user objects`() {
        val autorun = mapOf("options" to mapOf("develop" to true))
        val nationPol = mapOf("priority" to listOf("출병"))
        val genPol = mapOf("priority" to listOf("귀환"))
        val env = WorldEnvBuilder.aiEnvMap(
            year = 200,
            month = 3,
            startYear = 184,
            turnterm = 120,
            initYear = 184,
            initMonth = 1,
            autorunUser = autorun,
            npcNationPolicy = nationPol,
            npcGeneralPolicy = genPol,
        )
        assertEquals(autorun, env["autorun_user"])
        assertEquals(nationPol, env["npc_nation_policy"])
        assertEquals(genPol, env["npc_general_policy"])
    }

    // --- the existing precheck/full-mode env keys MUST NOT drift ---

    @Test
    fun `existing 2-arg envMap is unchanged (year startYear develCost camelCase)`() {
        val p2 = WorldEnvBuilder.envMap(year = 190, startYear = 184)
        assertEquals(listOf("year", "startYear", "develCost"), p2.keys.toList())
        assertEquals(190, (p2["year"] as Number).toInt())
        assertEquals(184, (p2["startYear"] as Number).toInt())
        assertEquals(EffectiveGameConst.develcost(190, 184), (p2["develCost"] as Number).toInt())
    }

    @Test
    fun `existing widened tick envMap is unchanged (month + currentEventID camelCase)`() {
        val tick = WorldEnvBuilder.envMap(year = 190, startYear = 184, month = 7)
        assertEquals(listOf("year", "startYear", "develCost", "month", "currentEventID"), tick.keys.toList())
        assertEquals(7, (tick["month"] as Number).toInt())
    }

    @Test
    fun `the AI superset is a SEPARATE map and does not collide with the precheck env keys`() {
        val ai = WorldEnvBuilder.aiEnvMap(
            year = 190,
            month = 1,
            startYear = 184,
            turnterm = 120,
            initYear = 184,
            initMonth = 1,
            autorunUser = null,
            npcNationPolicy = null,
            npcGeneralPolicy = null,
        )
        val precheck = WorldEnvBuilder.envMap(year = 190, startYear = 184)
        // The AI env uses snake_case "develcost"/"startyear"; precheck uses camelCase "develCost"/"startYear".
        assert(ai.containsKey("develcost") && !ai.containsKey("develCost"))
        assert(precheck.containsKey("develCost") && !precheck.containsKey("develcost"))
    }
}
