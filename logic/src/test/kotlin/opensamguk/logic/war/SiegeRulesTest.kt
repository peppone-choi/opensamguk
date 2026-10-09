package opensamguk.logic.war

import kotlin.test.*

class SiegeRulesTest {
    @Test fun `timeline extraction preserves phase insertion order and extra override bytes`() {
        val entry = SiegeRules.timelineEntry(opensamguk.logic.input.Phase(193, 7, 3), "ASSAULT_REPULSED", 6000, 900,
            "rounds" to 24, "replayHash" to "fixture-hash", "garrison" to 899)
        assertEquals(listOf("year", "month", "phase", "event", "morale", "garrison", "rounds", "replayHash"),
            entry.keys.toList())
        assertEquals(linkedMapOf<String, Any?>("year" to 193, "month" to 7, "phase" to 3,
            "event" to "ASSAULT_REPULSED", "morale" to 6000, "garrison" to 899,
            "rounds" to 24, "replayHash" to "fixture-hash"), entry)
    }

    @Test fun `appending timeline keeps the existing cap without modifying its input`() {
        val original = (1..CampaignBalance.SIEGE_TIMELINE_MAX).map { mapOf<String, Any?>("rounds" to it) }
        val next = mapOf<String, Any?>("rounds" to CampaignBalance.SIEGE_TIMELINE_MAX + 1)
        assertEquals(original.drop(1) + next, SiegeRules.appendTimeline(original, next))
        assertEquals(1, original.first()["rounds"])
        assertEquals(CampaignBalance.SIEGE_TIMELINE_MAX, original.size)
    }

    @Test fun `assault keeps the selected county and live siege authority`() {
        fun check(target: Int, active: Int?, turns: Int? = 3, battle: Boolean = false,
            corps: Boolean = true, hostile: Boolean = true) =
            SiegeRules.assaultReadiness(target, active, turns, battle, corps, hostile)
        assertNull(check(77, 77))
        assertEquals(SiegeRules.AssaultBlock.NOT_BESIEGING, check(77, null))
        assertEquals(SiegeRules.AssaultBlock.TARGET_CHANGED, check(77, 78))
        assertEquals(SiegeRules.AssaultBlock.TARGET_CHANGED, check(77, 77, hostile = false))
        assertEquals(SiegeRules.AssaultBlock.BATTLE_PENDING, check(77, 77, battle = true))
        assertEquals(SiegeRules.AssaultBlock.STATE_UNAVAILABLE, check(77, 77, corps = false))
        assertEquals(SiegeRules.AssaultBlock.ASSAULT_NOT_READY, check(77, 77, turns = 2))
    }

    @Test fun `maintenance checks rations before the approved two to one ratio`() {
        assertEquals(SiegeRules.Maintenance.UNFED, SiegeRules.maintenance(10_000, 1, fed = false))
        assertEquals(SiegeRules.Maintenance.INSUFFICIENT_RATIO, SiegeRules.maintenance(199, 100, fed = true))
        assertEquals(SiegeRules.Maintenance.MAINTAINED, SiegeRules.maintenance(200, 100, fed = true))
        assertTrue(SiegeRules.besiegerFed(100, 100)); assertFalse(SiegeRules.besiegerFed(100, 99))
    }

    @Test fun `a starved garrison surrenders on the fourth encircled phase and never twice`() {
        var morale = SiegeMorale.INITIAL_MORALE
        val results = (1..4).map { SiegeRules.settleTurn(morale, 100, 0).also { morale = it.morale } }
        assertEquals(listOf(7500, 5000, 2500, 0), results.map { it.morale })
        assertEquals(listOf(false, false, false, true), results.map { it.surrendered })
        assertEquals(10_000L, results.first().rationDemand)
        assertFalse(SiegeRules.settleTurn(0, 100, 0, alreadySurrendered = true).surrendered)
    }

    @Test fun `a fed garrison only eats what the warehouse holds and recovers`() {
        val partial = SiegeRules.settleTurn(5000, 100, 4_000)
        assertEquals(4_000L, partial.rationServed); assertEquals(3500, partial.morale)
        val full = SiegeRules.settleTurn(5000, 100, 1_000_000)
        assertEquals(10_000L, full.rationServed); assertEquals(6250, full.morale)
    }

    @Test fun `surrender demand needs both low morale and low trust`() {
        assertTrue(SiegeRules.surrenderDemandAccepted(3000, 50.0))
        assertFalse(SiegeRules.surrenderDemandAccepted(3001, 10.0))
        assertFalse(SiegeRules.surrenderDemandAccepted(0, 50.5))
    }
}
