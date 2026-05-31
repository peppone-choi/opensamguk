package opensamguk.logic.actions.nation

import opensamguk.common.rng.NoRng
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.Nation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T0.6 — the NationActionResolveContext + resolver registry foundation. The leaf families consume
 * the context (mutate draft + buffer side effects); the engine routes them. A NoRng-backed rng makes
 * the zero-draw invariant test-enforced for accept-flow leaves.
 */
class NationActionResolveContextTest {

    @AfterTest fun reset() = NationActionResolverRegistry.clear()

    private fun ctx(rng: RandUtil = RandUtil(NoRng())): NationActionResolveContext =
        NationActionResolveContext(
            nation = Nation(id = 1, level = 5, capitalCityId = 5, name = "촉", color = "#0f0"),
            rng = rng,
            generalId = 10,
            officerLevel = 12,
            year = 200, month = 3, date = "12:00",
            destNation = Nation(id = 2, level = 5, capitalCityId = 8, name = "위", color = "#00f"),
            diplomacyMatrix = mapOf((1 to 2) to Diplomacy(1, 2, state = 2), (2 to 1) to Diplomacy(2, 1, state = 2)),
            lastTurn = LastTurn(),
        )

    @Test
    fun `bidirectional diplomacy delta buffers BOTH directions`() {
        val c = ctx()
        c.setDiplomacyBidirectional(1, 2, state = 1, term = 24)
        val d = c.diplomacyDeltas()
        assertEquals(2, d.size)
        assertEquals(listOf(1 to 2, 2 to 1), d.map { it.fromNationId to it.toNationId })
        assertTrue(d.all { it.state == 1 && it.term == 24 })
    }

    @Test
    fun `the four log scopes + message + kv sinks buffer independently`() {
        val c = ctx()
        c.addActionLog("a"); c.addGeneralHistoryLog("gh"); c.addNationalHistoryLog("nh")
        c.addGlobalActionLog("ga"); c.addGlobalHistoryLog("gl"); c.addDestNationalHistoryLog("dnh")
        c.recordKv("nation_env", "1", "nationNotice", mapOf("text" to "x"))
        assertEquals(listOf("a"), c.actionLogs())
        assertEquals(listOf("gh"), c.generalHistoryLogs())
        assertEquals(listOf("nh"), c.nationalHistoryLogs())
        assertEquals(listOf("ga"), c.globalActionLogs())
        assertEquals(listOf("gl"), c.globalHistoryLogs())
        assertEquals(listOf("dnh"), c.destNationalHistoryLogs())
        assertEquals(1, c.kvWrites().size)
    }

    @Test
    fun `diplomacyOf reads the matrix and setResultTurn updates the result`() {
        val c = ctx()
        assertEquals(2, c.diplomacyOf(1, 2)!!.state)
        assertNull(c.diplomacyOf(3, 4))
        c.setResultTurn(LastTurn())
        assertEquals(LastTurn(), c.resultTurn)
    }

    @Test
    fun `accept-flow zero-draw invariant - a stray draw on the NoRng throws`() {
        val c = ctx(RandUtil(NoRng()))
        kotlin.test.assertFailsWith<opensamguk.common.rng.MustNotBeReachedException> { c.rng.nextRangeInt(1, 5) }
    }

    @Test
    fun `registry registers and resolves by action code and clear empties it`() {
        var ran = false
        NationActionResolverRegistry.register("che_선전포고") { ran = true }
        NationActionResolverRegistry.resolve("che_선전포고")!!.resolve(ctx())
        assertTrue(ran)
        assertNull(NationActionResolverRegistry.resolve("che_없음"))
        NationActionResolverRegistry.clear()
        assertNull(NationActionResolverRegistry.resolve("che_선전포고"))
    }
}
