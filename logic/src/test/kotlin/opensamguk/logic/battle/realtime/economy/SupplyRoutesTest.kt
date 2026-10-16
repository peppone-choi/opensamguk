package opensamguk.logic.battle.realtime.economy

import kotlin.test.*

internal fun EconomyLedger.routeStart(mode: EconomyMode = EconomyMode.REALTIME, p: EconomyParams = ready) =
    transport().createRoute("route", "A", "r", "cart", "a", "aa", mode, p).ok()
class SupplyRoutesTest {
    @Test fun `route automatically repeats physical delivery with no per trip turns`() {
        var s = seed().routeStart(EconomyMode.GENERAL_TURN)
        assertEquals(4L, s.totalTurns()); assertEquals(100L, s.store("a")!!.stock.grain)
        s = s.advanceRoute("load", "r", true, ready).ok()
        assertEquals(95L, s.store("a")!!.stock.grain); assertEquals(0L, s.store("aa")!!.stock.grain)
        assertEquals(5L, s.cargo("cart")!!.cargo.grain)
        s = s.advanceRoute("move1", "r", true, ready).ok(); assertEquals(0L, s.store("aa")!!.stock.grain)
        s = s.advanceRoute("move2", "r", true, ready).ok(); assertEquals(5L, s.store("aa")!!.stock.grain)
        repeat(7) { s = s.advanceRoute("tick$it", "r", true, ready).also { r -> assertEquals(0L, r.turnsSpent) }.ok() }
        assertEquals(2L, s.route("r")!!.trips); assertEquals(10L, s.store("aa")!!.stock.grain); assertEquals(4L, s.totalTurns())
    }
    @Test fun `disconnected or unknown graph causes no external credit and preserves in flight cargo`() {
        val base = seed().routeStart()
        rejected(base, base.advanceRoute("t", "r", false, ready), EconomyCode.NOT_DECIDED)
        rejected(base, base.advanceRoute("t", "r", null, ready), EconomyCode.NOT_DECIDED)
        val s = base.advanceRoute("l", "r", true, ready).ok()
        rejected(s, s.advanceRoute("t", "r", false, ready), EconomyCode.NOT_DECIDED)
        assertEquals(5L, s.cargo("cart")!!.cargo.grain); assertEquals(0L, s.store("aa")!!.stock.grain)
    }
    @Test fun `STOP then RECALL preserves cargo and returns it once without turns in both modes`() {
        for (mode in EconomyMode.entries) {
            var s = seed().routeStart(mode).advanceRoute("l", "r", true, ready).ok().advanceRoute("m", "r", true, ready).ok()
            val turns = s.totalTurns()
            s = s.commandRoute("stop", "A", "r", RouteCommand.STOP, mode).also { assertEquals(0L, it.turnsSpent) }.ok()
            rejected(s, s.advanceRoute("halted", "r", true, ready), EconomyCode.ROUTE_STOPPED)
            s = s.commandRoute("recall", "A", "r", RouteCommand.RECALL, mode).also { assertEquals(0L, it.turnsSpent) }.ok()
            assertEquals(5L, s.cargo("cart")!!.cargo.grain)
            s = s.advanceRoute("arrive", "r", false, ready).ok()
            assertEquals(100L, s.store("a")!!.stock.grain); assertEquals(0L, s.store("aa")!!.stock.grain)
            assertEquals(ResourceBundle.ZERO, s.cargo("cart")!!.cargo); assertEquals(turns, s.totalTurns())
            assertEquals(EconomyCode.ALREADY_APPLIED, s.advanceRoute("arrive", "r", false, ready).code)
        }
    }
    @Test fun `route ownership quantity period and turn seams are fail closed`() {
        val s = seed().transport()
        rejected(s, s.createRoute("r", "A", "r", "cart", "a", "b", EconomyMode.REALTIME, ready), EconomyCode.NOT_OWNER)
        rejected(s, s.createRoute("r", "A", "r", "cart", "a", "aa", EconomyMode.GENERAL_TURN, ready.copy(routeTurnCost = null)), EconomyCode.NOT_DECIDED)
        for (p in listOf(ready.copy(routeAmount = null), ready.copy(routeLegTicks = null), ready.copy(status = ParameterStatus.PROPOSED))) {
            val n = s.createRoute("r", "A", "r", "cart", "a", "aa", EconomyMode.REALTIME, p).ok()
            rejected(n, n.advanceRoute("t", "r", true, p), EconomyCode.NOT_DECIDED)
        }
    }
    @Test fun `insufficient route stock waits and unclassified modifications stay undecided`() {
        val s = seed(0).routeStart()
        rejected(s, s.advanceRoute("t", "r", true, ready), EconomyCode.INSUFFICIENT_STOCK)
        for (mode in EconomyMode.entries) for (c in listOf(RouteCommand.CHANGE_AMOUNT, RouteCommand.PAUSE, RouteCommand.REMOVE))
            rejected(s, s.commandRoute("c", "A", "r", c, mode), EconomyCode.NOT_DECIDED)
    }
    @Test fun `destination and item change is one turn instruction not a transfer`() {
        var s = seed().routeStart()
        s = s.changeRoute("change", "A", "r", "aa", EconomyMode.GENERAL_TURN, ready.copy(routeAmount = ResourceBundle(stone = 2))).ok()
        assertEquals(4L, s.totalTurns()); assertEquals(100L, s.store("a")!!.stock.stone)
        s = s.advanceRoute("t", "r", true, ready).ok()
        assertEquals(2L, s.cargo("cart")!!.cargo.stone)
        rejected(s, s.depositCargo("bypass", "A", "cart", "a", 0, 1, ready), EconomyCode.ROUTE_BUSY)
        rejected(s, s.loadCargo("bypass", "A", "cart", "a", ResourceBundle(grain = 1), ready), EconomyCode.ROUTE_BUSY)
    }
}
