package opensamguk.logic.war.hwiha

import kotlin.test.*

class HwihaSiegeRulesTest {
    @Test fun `maintenance checks rations before the approved two to one ratio`() {
        assertEquals(HwihaSiegeRules.Maintenance.UNFED, HwihaSiegeRules.maintenance(10_000, 1, fed = false))
        assertEquals(HwihaSiegeRules.Maintenance.INSUFFICIENT_RATIO, HwihaSiegeRules.maintenance(199, 100, fed = true))
        assertEquals(HwihaSiegeRules.Maintenance.MAINTAINED, HwihaSiegeRules.maintenance(200, 100, fed = true))
        assertTrue(HwihaSiegeRules.besiegerFed(100, 100)); assertFalse(HwihaSiegeRules.besiegerFed(100, 99))
    }

    @Test fun `a starved garrison surrenders on the fourth encircled phase and never twice`() {
        var morale = HwihaSiegeMorale.INITIAL_MORALE
        val results = (1..4).map { HwihaSiegeRules.settleTurn(morale, 100, 0).also { morale = it.morale } }
        assertEquals(listOf(7500, 5000, 2500, 0), results.map { it.morale })
        assertEquals(listOf(false, false, false, true), results.map { it.surrendered })
        assertEquals(10_000L, results.first().rationDemand)
        assertFalse(HwihaSiegeRules.settleTurn(0, 100, 0, alreadySurrendered = true).surrendered)
    }

    @Test fun `a fed garrison only eats what the warehouse holds and recovers`() {
        val partial = HwihaSiegeRules.settleTurn(5000, 100, 4_000)
        assertEquals(4_000L, partial.rationServed); assertEquals(3500, partial.morale)
        val full = HwihaSiegeRules.settleTurn(5000, 100, 1_000_000)
        assertEquals(10_000L, full.rationServed); assertEquals(6250, full.morale)
    }

    @Test fun `surrender demand needs both low morale and low trust`() {
        assertTrue(HwihaSiegeRules.surrenderDemandAccepted(3000, 50.0))
        assertFalse(HwihaSiegeRules.surrenderDemandAccepted(3001, 10.0))
        assertFalse(HwihaSiegeRules.surrenderDemandAccepted(0, 50.5))
    }
}
