package opensamguk.logic.battle.realtime.economy

import kotlin.test.*

internal val farmReady = ready.copy(farmYield = 20, farmReseedTimber = 7)

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
        var s = seed().worker().registerNode("n", "farm", NodeKind.FARM, ResourceKind.GRAIN, null, farmReady, owner = "A").ok()
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
        val s = seed().worker().registerNode("n1", "f1", NodeKind.FARM, ResourceKind.GRAIN, null, farmReady, owner = "A").ok()
            .registerNode("n2", "f2", NodeKind.FARM, ResourceKind.GRAIN, null, farmReady, owner = "A").ok()
            .assignFarm("a", "A", "f1", setOf("worker"), ready).ok()
        rejected(s, s.assignFarm("b", "A", "f2", setOf("worker"), ready), EconomyCode.INVALID_INPUT)
    }
    @Test fun `unknown amounts and farm values do not progress`() {
        val base = seed().worker()
        rejected(base, base.registerNode("n", "n", NodeKind.DEPOSIT, ResourceKind.GOLD, null, ready), EconomyCode.NOT_DECIDED)
        val s = base.registerNode("n", "f", NodeKind.FARM, ResourceKind.GRAIN, null, farmReady, owner = "A").ok().assignFarm("a", "A", "f", setOf("worker"), ready).ok()
        for (p in listOf(ready.copy(gatherAmount = null), ready.copy(carrierCapacity = null), ready.copy(farmTicks = null), ready.copy(farmWorkers = null)))
            rejected(s, s.gather("g", "A", "f", "worker", p), EconomyCode.NOT_DECIDED)
    }
    @Test fun `capacity rejection does not decrement node or farm progress`() {
        val s = seed().worker().registerNode("n", "n", NodeKind.DEPOSIT, ResourceKind.IRON, 10, ready).ok()
        rejected(s, s.gather("g", "A", "n", "worker", ready.copy(carrierCapacity = 2)), EconomyCode.CAPACITY_EXCEEDED)
    }

    private fun farm(timber: Long = 20, params: EconomyParams = farmReady.copy(farmYield = 7, farmTicks = 1)) =
        EconomyLedger(listOf(
            EconomyStore("a", "A", ResourceBundle(timber = timber, grain = 100)),
            EconomyStore("aa", "A", ResourceBundle.ZERO),
            EconomyStore("b", "B", ResourceBundle(timber = 100)),
        )).worker().registerNode("f", "farm", NodeKind.FARM, ResourceKind.GRAIN, null, params, owner = "A").ok()
            .assignFarm("assign", "A", "farm", setOf("worker"), params).ok()

    private val small = farmReady.copy(farmYield = 7, farmTicks = 1)
    private fun EconomyLedger.harvest(prefix: String) = gather("$prefix-1", "A", "farm", "worker", small).ok()
        .gather("$prefix-2", "A", "farm", "worker", small).ok()

    @Test fun `farm registration accepts only confirmed finite yield grain and explicit owner`() {
        val s = seed()
        rejected(s, s.registerNode("f", "f", NodeKind.FARM, ResourceKind.GRAIN, 20, farmReady, "A"), EconomyCode.INVALID_INPUT)
        rejected(s, s.registerNode("f", "f", NodeKind.FARM, ResourceKind.GRAIN, null, ready, "A"), EconomyCode.NOT_DECIDED)
        rejected(s, s.registerNode("f", "f", NodeKind.FARM, ResourceKind.GRAIN, null, farmReady), EconomyCode.INVALID_INPUT)
        rejected(s, s.registerNode("f", "f", NodeKind.FARM, ResourceKind.STONE, null, farmReady, "A"), EconomyCode.INVALID_INPUT)
        for (p in listOf(farmReady.copy(farmYield = 0), farmReady.copy(farmYield = -1)))
            rejected(s, s.registerNode("f", "f", NodeKind.FARM, ResourceKind.GRAIN, null, p, "A"), EconomyCode.INVALID_INPUT)
        assertEquals(20L, s.registerNode("f", "f", NodeKind.FARM, ResourceKind.GRAIN, null, farmReady, "A").ok().node("f")!!.remaining)
    }
    @Test fun `timber reseed restores exactly one finite cycle and receipts survive mutable cycle changes`() {
        var s = farm().harvest("first")
        assertEquals(7L, s.cargo("worker")!!.cargo.grain)
        rejected(s, s.gather("empty", "A", "farm", "worker", small), EconomyCode.NODE_DEPLETED)
        val before = s.stock("A")
        s = s.reseedFarm("reseed", "A", "farm", "a", small).ok()
        assertEquals(before - ResourceBundle(timber = 7), s.stock("A"))
        assertEquals(ResourceBundle(timber = 7), s.state.spent)
        assertEquals(7L, s.node("farm")!!.remaining); assertEquals(2L, s.node("farm")!!.cycle)
        assertEquals(0L, s.node("farm")!!.progress); assertEquals(setOf("worker"), s.node("farm")!!.workers)
        assertEquals(7L, s.node("farm")!!.reseedDebits.sumOf { it.amount.timber })
        assertEquals(EconomyCode.ALREADY_APPLIED, s.reseedFarm("reseed", "A", "farm", "a", small).code)
        rejected(s, s.reseedFarm("reseed", "A", "farm", "aa", small), EconomyCode.COMMAND_CONFLICT)
        rejected(s, s.reseedFarm("again", "A", "farm", "a", small), EconomyCode.NOT_DEPLETED)
        s = s.harvest("second")
        assertEquals(14L, s.cargo("worker")!!.cargo.grain)
        assertEquals(14L, s.node("farm")!!.produced); assertEquals(14L, s.node("farm")!!.initial)
        rejected(s, s.gather("empty-again", "A", "farm", "worker", small), EconomyCode.NODE_DEPLETED)
        val hash = s.sha256()
        assertEquals(EconomyCode.ALREADY_APPLIED, s.reseedFarm("reseed", "A", "farm", "a", small).code)
        assertEquals(hash, s.sha256()); conservation(s)
    }
    @Test fun `reseed debits preferred then sorted owner stores and never another owner`() {
        var s = farm(3).harvest("h")
        s = s.transfer("fund", "B", "b", "aa", ResourceBundle(timber = 10), ready).ok()
        val b = s.stock("B")
        s = s.reseedFarm("r", "A", "farm", "a", small).ok()
        assertEquals(listOf("a", "aa"), s.node("farm")!!.reseedDebits.map { it.storeId })
        assertEquals(listOf(3L, 4L), s.node("farm")!!.reseedDebits.map { it.amount.timber })
        assertEquals(b, s.stock("B")); assertEquals(100L, s.stock("A").grain); conservation(s)
    }
    @Test fun `insufficient timber preserves empty owned farm indefinitely and same rejected id can retry after deposit`() {
        var s = farm(6).harvest("h")
        val empty = s.node("farm")!!; val hash = s.sha256()
        rejected(s, s.reseedFarm("retry", "A", "farm", "a", small), EconomyCode.INSUFFICIENT_STOCK)
        repeat(30) { rejected(s, s.gather("wait-$it", "A", "farm", "worker", small), EconomyCode.NODE_DEPLETED) }
        assertEquals(empty, s.node("farm")); assertEquals("A", empty.owner)
        assertEquals(0L, empty.remaining); assertEquals(1L, empty.cycle)
        assertEquals(hash, s.sha256()); assertFalse("retry" in s.state.receipts)
        s = s.registerNode("wood", "wood", NodeKind.DEPOSIT, ResourceKind.TIMBER, 1, ready).ok()
            .gather("wood-g", "A", "wood", "worker", ready).ok()
            .depositCargo("wood-d", "A", "worker", "a", 0, 1, ready).ok()
            .reseedFarm("retry", "A", "farm", "a", small).ok().harvest("resumed")
        assertEquals(2L, s.node("farm")!!.cycle); assertEquals(0L, s.stock("A").timber)
        assertEquals(7L, s.cargo("worker")!!.cargo.grain); conservation(s)
    }
    @Test fun `reseed rejects active foreign unknown and invalid policies atomically`() {
        val live = farm()
        rejected(live, live.reseedFarm("r", "A", "farm", "a", small), EconomyCode.NOT_DEPLETED)
        val s = live.harvest("h")
        rejected(s, s.reseedFarm("r", "B", "farm", "b", small), EconomyCode.NOT_OWNER)
        rejected(s, s.assignFarm("steal", "B", "farm", emptySet(), small), EconomyCode.NOT_OWNER)
        rejected(s, s.reseedFarm("r", "A", "farm", "b", small), EconomyCode.NOT_OWNER)
        for (p in listOf(small.copy(farmYield = null), small.copy(farmReseedTimber = null), small.copy(status = ParameterStatus.PROPOSED)))
            rejected(s, s.reseedFarm("r", "A", "farm", "a", p), EconomyCode.NOT_DECIDED)
        for (p in listOf(small.copy(farmYield = 0), small.copy(farmReseedTimber = 0), small.copy(farmReseedTimber = -1)))
            rejected(s, s.reseedFarm("r", "A", "farm", "a", p), EconomyCode.INVALID_INPUT)
        val mine = s.registerNode("mine", "mine", NodeKind.DEPOSIT, ResourceKind.STONE, 0, ready).ok()
        rejected(mine, mine.reseedFarm("r", "A", "mine", "a", small), EconomyCode.INVALID_INPUT)
    }
    @Test fun `farm cycle yield overflow rolls back planned debit and receipt`() {
        val huge = small.copy(farmYield = Long.MAX_VALUE, gatherAmount = Long.MAX_VALUE, carrierCapacity = Long.MAX_VALUE)
        val s = farm(params = huge).gather("g", "A", "farm", "worker", huge).ok()
        rejected(s, s.reseedFarm("r", "A", "farm", "a", huge), EconomyCode.INVALID_INPUT)
        assertEquals(20L, s.stock("A").timber); assertTrue(s.node("farm")!!.reseedDebits.isEmpty())
    }
}
