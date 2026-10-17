package opensamguk.logic.battle.realtime.economy

import kotlin.test.*

class CargoTest {
    @Test fun `non destructive hit never drops cargo while destruction drops only once`() {
        var s = seed().transport().loadCargo("l", "A", "cart", "a", ResourceBundle(grain = 10), ready).ok()
        s = s.hitCarrier("hit", "cart").ok(); assertEquals(10L, s.cargo("cart")!!.cargo.grain); assertTrue(s.state.ground.isEmpty())
        s = s.destroyCarrier("dead", "cart", ready).ok()
        val g = s.state.ground.values.single(); assertEquals(10L, g.cargo.grain); assertEquals("A", g.originalOwner)
        assertEquals(EconomyCode.ALREADY_APPLIED, s.destroyCarrier("dead", "cart", ready).code)
        rejected(s, s.destroyCarrier("dead2", "cart", ready), EconomyCode.INVALID_INPUT)
    }
    @Test fun `only worker or transport may loot and loot remains unavailable until deposited`() {
        var s = seed().transport().loadCargo("l", "A", "cart", "a", ResourceBundle(grain = 10), ready).ok().destroyCarrier("d", "cart", ready).ok()
            .admitUnit("soldier", "B", "soldier", HousingKind.SOLDIER).ok().registerCarrier("sc", "B", "soldier", CarrierKind.SOLDIER).ok().worker("B", "bw")
        val g = s.state.ground.keys.single()
        rejected(s, s.pickupCargo("bad", "B", "soldier", g, ready), EconomyCode.CARRIER_NOT_ALLOWED)
        s = s.pickupCargo("good", "B", "bw", g, ready).ok(); assertEquals(100L, s.stock("B").grain); assertEquals(10L, s.cargo("bw")!!.cargo.grain)
        s = s.depositCargo("deposit", "B", "bw", "b", 0, 1, ready).ok(); assertEquals(110L, s.stock("B").grain)
        assertTrue(s.state.ground.isEmpty())
    }
    @Test fun `cargo loss seam never guesses fraction or rounding`() {
        val s = seed().transport().loadCargo("l", "A", "cart", "a", ResourceBundle(grain = 5), ready).ok()
        rejected(s, s.destroyCarrier("d", "cart", ready.copy(dropRetainedNumerator = null)), EconomyCode.NOT_DECIDED)
        rejected(s, s.destroyCarrier("d", "cart", ready.copy(dropRetainedNumerator = 1, dropRetainedDenominator = 2)), EconomyCode.NOT_DECIDED)
        val n = s.destroyCarrier("d", "cart", ready.copy(dropRetainedNumerator = 2, dropRetainedDenominator = 5)).ok()
        assertEquals(2L, n.state.ground.values.single().cargo.grain); assertEquals(3L, n.state.lost.grain)
    }
    @Test fun `deposit requires collection policy actual arrival own building and elapsed time`() {
        val s = seed().transport().loadCargo("l", "A", "cart", "a", ResourceBundle(grain = 5), ready).ok()
        for (p in listOf(ready.copy(collectionKinds = null), ready.copy(collectionRadius = null), ready.copy(depositTicks = null)))
            rejected(s, s.depositCargo("d", "A", "cart", "a", 0, 1, p), EconomyCode.NOT_DECIDED)
        rejected(s, s.depositCargo("d", "A", "cart", "a", 2, 1, ready), EconomyCode.NOT_AT_DESTINATION)
        rejected(s, s.depositCargo("d", "A", "cart", "a", 0, 0, ready), EconomyCode.NOT_AT_DESTINATION)
        rejected(s, s.depositCargo("d", "A", "cart", "b", 0, 1, ready), EconomyCode.NOT_OWNER)
    }
    @Test fun `only physical exit arrival recovers retreat cargo`() {
        val s = seed().worker().loadCargo("l", "A", "worker", "a", ResourceBundle(grain = 5), ready).ok()
        rejected(s, s.escapeCargo("e", "worker", null), EconomyCode.NOT_DECIDED)
        rejected(s, s.escapeCargo("e", "worker", false), EconomyCode.NOT_AT_DESTINATION)
        val n = s.escapeCargo("e", "worker", true).ok()
        assertEquals(5L, n.state.escaped.grain); assertEquals(0L, n.housingUsed("A")); assertTrue(n.state.ground.isEmpty())
        assertEquals(EconomyCode.ALREADY_APPLIED, n.escapeCargo("e", "worker", true).code)
    }
}
