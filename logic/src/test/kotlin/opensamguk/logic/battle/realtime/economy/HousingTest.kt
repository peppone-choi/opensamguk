package opensamguk.logic.battle.realtime.economy

import kotlin.test.*

class HousingTest {
    @Test fun `housing is per general and shares workers and soldiers`() {
        val p = ready.copy(housingPerBuilding = 1)
        var s = seed().housing(params = p).housing("B", "bh", p).worker().production(owner = "A").production("bo", owner = "B")
        assertEquals(1L, s.housingUsed("A")); assertEquals(0L, s.housingUsed("B"))
        rejected(s, s.advanceProduction("a", "o", true, p), EconomyCode.INSUFFICIENT_HOUSING)
        s = s.advanceProduction("b", "bo", true, p).ok()
        assertEquals(1L, s.order("bo")!!.progress)
    }
    @Test fun `destroyed housing and reinforcement preserve overflow units and pause only production`() {
        var s = seed().housing(params = ready.copy(housingPerBuilding = 1)).production()
            .admitUnit("reinforce1", "A", "s1", HousingKind.SOLDIER).ok().admitUnit("reinforce2", "A", "s2", HousingKind.SOLDIER).ok()
        assertEquals(2L, s.housingUsed("A")); assertEquals(1L, s.housingCapacity("A"))
        s = s.destroyHousing("destroy", "house").ok(); assertEquals(2L, s.housingUsed("A"))
        rejected(s, s.advanceProduction("p", "o", true, ready), EconomyCode.INSUFFICIENT_HOUSING)
        s = s.housing(id = "replacement").advanceProduction("resume", "o", true, ready).ok()
        assertEquals(1L, s.order("o")!!.progress)
    }
    @Test fun `leave frees housing and unknown capacity or reservation blocks production`() {
        val s = seed().housing().production().admitUnit("a", "A", "u", HousingKind.SOLDIER).ok().leaveUnit("l", "u").ok()
        assertEquals(0L, s.housingUsed("A"))
        rejected(s, s.addHousing("h", "A", "h", ready.copy(housingPerBuilding = null)), EconomyCode.NOT_DECIDED)
        rejected(s, s.advanceProduction("p", "o", true, ready.copy(reserveHousingDuringProduction = null)), EconomyCode.NOT_DECIDED)
    }
    @Test fun `injected reservation policy prevents two producers from reserving one place`() {
        val p = ready.copy(housingPerBuilding = 1, reserveHousingDuringProduction = true)
        var s = seed().housing(params = p).production()
        s = s.enqueue("e2", "A", "o2", "other", "a", "popA", recipe, 1, EconomyMode.REALTIME, p).ok()
        s = s.advanceProduction("p1", "o", true, p).ok()
        rejected(s, s.advanceProduction("p2", "o2", true, p), EconomyCode.INSUFFICIENT_HOUSING)
        s = s.cancelProduction("c", "A", "o", EconomyMode.REALTIME, p).ok().advanceProduction("p3", "o2", true, p).ok()
        assertEquals(1L, s.order("o2")!!.progress)
    }
}
