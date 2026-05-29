package opensamguk.logic.statview

import opensamguk.common.constants.EffectiveGameConst
import opensamguk.logic.domain.WorldEnv
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldEnvBuilderTest {

    @Test
    fun `worldEnv derives develCost via EffectiveGameConst`() {
        val env = WorldEnvBuilder.worldEnv(year = 200, startYear = 184)
        assertEquals(200, env.year)
        assertEquals(184, env.startYear)
        // (200 - 184 + 10) * 2 = 52
        assertEquals(EffectiveGameConst.develcost(200, 184), env.develCost)
        assertEquals(52, env.develCost)
        assertEquals(16, env.relYear)
    }

    @Test
    fun `envMap carries the exact keys CommerceInvestment_envOf reads`() {
        val env = WorldEnvBuilder.envMap(year = 190, startYear = 184)
        assertEquals(setOf("year", "startYear", "develCost"), env.keys)
        assertEquals(190, (env["year"] as Number).toInt())
        assertEquals(184, (env["startYear"] as Number).toInt())
        assertEquals(EffectiveGameConst.develcost(190, 184), (env["develCost"] as Number).toInt())
    }

    @Test
    fun `envMap keys are insertion-ordered year startYear develCost`() {
        val env = WorldEnvBuilder.envMap(year = 190, startYear = 184)
        assertEquals(listOf("year", "startYear", "develCost"), env.keys.toList())
    }

    @Test
    fun `envMap from WorldEnv equals envMap from year startYear (single source)`() {
        val typed = WorldEnv(year = 220, startYear = 184, develCost = 999) // bogus develCost ignored
        val fromTyped = WorldEnvBuilder.envMap(typed)
        val fromScalars = WorldEnvBuilder.envMap(220, 184)
        assertEquals(fromScalars, fromTyped)
        // develCost is RE-derived from year/startYear, never trusting a passed-in value.
        assertEquals(EffectiveGameConst.develcost(220, 184), (fromTyped["develCost"] as Number).toInt())
    }

    @Test
    fun `precheck and full-mode env cannot drift — same helper yields identical env`() {
        // The single-source guarantee: whichever site (precheck E2 / full F3) calls the helper
        // with the same year/startYear gets byte-identical env keys + values.
        val precheckEnv = WorldEnvBuilder.envMap(year = 205, startYear = 184)
        val fullModeEnv = WorldEnvBuilder.envMap(year = 205, startYear = 184)
        assertEquals(precheckEnv, fullModeEnv)
        assertEquals(precheckEnv.keys.toList(), fullModeEnv.keys.toList())
    }
}
