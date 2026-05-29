package opensamguk.logic.statview

import opensamguk.common.constants.EffectiveGameConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FC2 — the widened [WorldEnvBuilder.envMap] carries `month` + `currentEventID` for the monthly tick.
 *
 * The SAME builder feeds precheck (E2) and the tick (F4): the existing P2 2-arg `envMap(year, startYear)`
 * stays byte-identical (3 keys: year/startYear/develCost) so precheck never drifts, and a widened
 * overload adds `month` (PHP event-env key `$env['month']`, read by Date/DateRelative conditions +
 * Income/RandomizeCityTradeRate/UpdateNationLevel Actions) + `currentEventID` (PHP `$env['currentEventID']`,
 * the dispatcher injects it per event row; DeleteEvent/AutoDeleteInvader read it). Keys stay
 * insertion-ordered.
 */
class WorldEnvBuilderMonthTest {

    @Test
    fun `widened envMap carries month after develCost in insertion order`() {
        val env = WorldEnvBuilder.envMap(year = 190, startYear = 184, month = 7)
        assertEquals(listOf("year", "startYear", "develCost", "month", "currentEventID"), env.keys.toList())
        assertEquals(190, (env["year"] as Number).toInt())
        assertEquals(184, (env["startYear"] as Number).toInt())
        assertEquals(EffectiveGameConst.develcost(190, 184), (env["develCost"] as Number).toInt())
        assertEquals(7, (env["month"] as Number).toInt())
    }

    @Test
    fun `currentEventID defaults null (absent dispatch) and is settable per dispatch`() {
        val noDispatch = WorldEnvBuilder.envMap(year = 190, startYear = 184, month = 1)
        assertTrue(noDispatch.containsKey("currentEventID"), "key present so it is settable per dispatch")
        assertNull(noDispatch["currentEventID"], "currentEventID is null outside a dispatch")

        val inDispatch = WorldEnvBuilder.envMap(year = 190, startYear = 184, month = 1, currentEventID = 42)
        assertEquals(42, (inDispatch["currentEventID"] as Number).toInt())
        assertEquals(listOf("year", "startYear", "develCost", "month", "currentEventID"), inDispatch.keys.toList())
    }

    @Test
    fun `the existing P2 2-arg envMap is unchanged (no month, no currentEventID) -- precheck cannot drift`() {
        val p2 = WorldEnvBuilder.envMap(year = 190, startYear = 184)
        assertEquals(setOf("year", "startYear", "develCost"), p2.keys)
        assertEquals(listOf("year", "startYear", "develCost"), p2.keys.toList())
        // The P2 env assertion round-trips byte-identically (the same single shared builder).
        assertEquals(190, (p2["year"] as Number).toInt())
        assertEquals(184, (p2["startYear"] as Number).toInt())
        assertEquals(EffectiveGameConst.develcost(190, 184), (p2["develCost"] as Number).toInt())
    }

    @Test
    fun `the widened env superset preserves the exact P2 year-startYear-develCost values`() {
        val p2 = WorldEnvBuilder.envMap(year = 205, startYear = 184)
        val widened = WorldEnvBuilder.envMap(year = 205, startYear = 184, month = 3)
        // The widened map is a strict superset: the P2 keys + values are identical, just extended.
        for (k in listOf("year", "startYear", "develCost")) {
            assertEquals(p2[k], widened[k], "P2 key $k must be byte-identical in the widened env")
        }
    }
}
