package opensamguk.logic.battle.realtime.economy

import kotlin.test.*

class ResourceNodesTest {
    @Test fun `node then cargo then deposit is the only spendable path`() {
        var s = seed(0).worker().housing().production().registerNode("n", "n", NodeKind.DEPOSIT, ResourceKind.GRAIN, 5, ready).ok()
        s = s.gather("g", "A", "n", "worker", ready).ok()
        assertEquals(0L, s.node("n")!!.remaining); assertEquals(5L, s.cargo("worker")!!.cargo.grain)
        assertEquals(ResourceBundle.ZERO, s.stock("A"))
        rejected(s, s.advanceProduction("p", "o", true, ready), EconomyCode.INSUFFICIENT_STOCK)
        s = s.depositCargo("d", "A", "worker", "a", 1, 1, ready).ok()
        assertEquals(5L, s.stock("A").grain); assertEquals(ResourceBundle.ZERO, s.cargo("worker")!!.cargo)
        rejected(s, s.gather("g2", "A", "n", "worker", ready), EconomyCode.NODE_DEPLETED)
        assertEquals(EconomyCode.ALREADY_APPLIED, s.depositCargo("d", "A", "worker", "a", 1, 1, ready).code)
    }
    @Test fun `finite node last extraction uses only remaining and cannot regenerate`() {
        var s = seed().worker().registerNode("n", "n", NodeKind.DEPOSIT, ResourceKind.STONE, 7, ready).ok()
        s = s.gather("g1", "A", "n", "worker", ready).ok().gather("g2", "A", "n", "worker", ready).ok()
        assertEquals(7L, s.cargo("worker")!!.cargo.stone); assertEquals(0L, s.node("n")!!.remaining)
        rejected(s, s.registerNode("new", "n", NodeKind.DEPOSIT, ResourceKind.STONE, 7, ready), EconomyCode.DUPLICATE_ID)
    }
    @Test fun `farm pauses without assigned living workers and resumes saved progress`() {
        var s = seed().worker().registerNode("n", "farm", NodeKind.FARM, ResourceKind.GRAIN, 20, ready).ok()
        rejected(s, s.gather("g0", "A", "farm", "worker", ready), EconomyCode.WORKERS_REQUIRED)
        s = s.assignFarm("assign", "A", "farm", setOf("worker"), ready).ok().gather("g1", "A", "farm", "worker", ready).ok()
        assertEquals(1L, s.node("farm")!!.progress); assertEquals(20L, s.node("farm")!!.remaining)
        s = s.assignFarm("leave", "A", "farm", emptySet(), ready).ok()
        rejected(s, s.gather("g2", "A", "farm", "worker", ready), EconomyCode.WORKERS_REQUIRED)
        s = s.assignFarm("back", "A", "farm", setOf("worker"), ready).ok().gather("g3", "A", "farm", "worker", ready).ok()
        assertEquals(15L, s.node("farm")!!.remaining); assertEquals(5L, s.cargo("worker")!!.cargo.grain)
        s = s.destroyCarrier("dead", "worker", ready).ok()
        assertTrue(s.node("farm")!!.workers.isEmpty())
    }
    @Test fun `farm worker cannot be assigned simultaneously to two farms`() {
        val s = seed().worker().registerNode("n1", "f1", NodeKind.FARM, ResourceKind.GRAIN, 10, ready).ok()
            .registerNode("n2", "f2", NodeKind.FARM, ResourceKind.GRAIN, 10, ready).ok()
            .assignFarm("a", "A", "f1", setOf("worker"), ready).ok()
        rejected(s, s.assignFarm("b", "A", "f2", setOf("worker"), ready), EconomyCode.INVALID_INPUT)
    }
    @Test fun `unknown amounts and farm values do not progress`() {
        val base = seed().worker()
        rejected(base, base.registerNode("n", "n", NodeKind.DEPOSIT, ResourceKind.GOLD, null, ready), EconomyCode.NOT_DECIDED)
        val s = base.registerNode("n", "f", NodeKind.FARM, ResourceKind.GRAIN, 10, ready).ok().assignFarm("a", "A", "f", setOf("worker"), ready).ok()
        for (p in listOf(ready.copy(gatherAmount = null), ready.copy(carrierCapacity = null), ready.copy(farmTicks = null), ready.copy(farmWorkers = null)))
            rejected(s, s.gather("g", "A", "f", "worker", p), EconomyCode.NOT_DECIDED)
    }
    @Test fun `capacity rejection does not decrement node or farm progress`() {
        val s = seed().worker().registerNode("n", "n", NodeKind.DEPOSIT, ResourceKind.IRON, 10, ready).ok()
        rejected(s, s.gather("g", "A", "n", "worker", ready.copy(carrierCapacity = 2)), EconomyCode.CAPACITY_EXCEEDED)
    }
}
