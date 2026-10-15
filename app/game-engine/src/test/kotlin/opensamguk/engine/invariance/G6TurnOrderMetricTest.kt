package opensamguk.engine.invariance

import kotlin.test.*
import opensamguk.engine.invariance.G6TurnOrderMetricFixture.Probe
import opensamguk.engine.invariance.G6TurnOrderMetricFixture.Scenario

class G6TurnOrderMetricTest {
    private val fixture = G6TurnOrderMetricFixture()

    @Test fun `real encounter yields one ordered metric row`() {
        val report = fixture.replay("encounter")
        fixture.assertOneEncounter(report)
        val row = report.rows.single()
        assertEquals(2, report.starts.size)
        assertEquals(2, row.attacker.tieGroupSize)
        assertEquals(2, row.defender.tieGroupSize)
        assertEquals(1, row.attacker.idRank)
        assertEquals(2, row.defender.idRank)
        assertEquals(report.starts.indexOf(100) + 1, row.defender.ordinal)
        assertNull(row.defender.absentReason)
        assertEquals(listOf("UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE"),
            listOf(row.installedStratagemTriggered, row.responseStratagemTriggered,
                row.interceptPolicyTriggered, row.evadePolicyTriggered))
    }

    @Test fun `same input yields byte identical rows`() {
        val first = fixture.replay("determinism-a")
        val second = fixture.replay("determinism-b")
        fixture.assertOneEncounter(first)
        fixture.assertOneEncounter(second)
        assertEquals(first.bytes, second.bytes)
    }

    @Test fun `no encounter produces no metric rows`() {
        val report = fixture.replay("no-encounter", Scenario.NO_ENCOUNTER)
        assertEquals(2, report.starts.size)
        assertTrue(report.rows.isEmpty())
        assertFalse(report.battleRecordPresent)
        assertEquals(listOf("NotPending", "NotPending"), report.resolutions)
    }

    @Test fun `unavailable and disbanded encounters produce no metric rows`() {
        for ((scenario, resolution) in listOf(Scenario.UNAVAILABLE to "Unavailable", Scenario.DISBANDED to "Disbanded")) {
            val report = fixture.replay(scenario.name.lowercase(), scenario)
            assertTrue(resolution in report.resolutions)
            assertTrue(report.rows.isEmpty())
            assertFalse(report.battleRecordPresent)
        }
    }

    @Test fun `defender without personal turn has explicit absent ordinal`() {
        val report = fixture.replay("defender-not-due", Scenario.DEFENDER_NOT_DUE)
        fixture.assertOneEncounter(report)
        assertNull(report.rows.single().defender.ordinal)
        assertEquals("NO_PERSONAL_TURN_IN_PHASE", report.rows.single().defender.absentReason)
    }

    @Test fun `permanent negative control dropped hook fails the same cardinality assertion`() {
        val report = fixture.replay("drop-hook-negative-control", probe = Probe.DROP_HOOK)
        assertTrue(report.rows.isEmpty())
        val failure = assertFailsWith<AssertionError> { fixture.assertOneEncounter(report) }
        assertTrue(failure.message.orEmpty().contains("G6 one resolved encounter"))
    }
}
