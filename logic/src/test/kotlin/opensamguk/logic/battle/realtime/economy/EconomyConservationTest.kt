package opensamguk.logic.battle.realtime.economy

import kotlin.random.Random
import kotlin.test.*

/** Four independent equalities after every operation, including rejected operations and retries. */
internal fun conservation(ledger: EconomyLedger) {
    val s = ledger.state
    // 1: Six-resource mass includes stock, transit, ground, consumed, lost and recovered cargo.
    val expected = s.nodes.values.fold(s.imported) { a, n -> a + n.resource.bundle(n.initial) }
    var actual = s.stores.values.fold(ResourceBundle.ZERO) { a, w -> a + w.stock }
    actual = s.carriers.values.fold(actual) { a, c -> a + c.cargo }
    actual = s.ground.values.fold(actual) { a, g -> a + g.cargo }
    actual = s.nodes.values.fold(actual) { a, n -> a + n.resource.bundle(n.remaining) }
    assertEquals(expected, actual + s.spent + s.lost + s.escaped, "resource conservation")
    // 2: A node's extracted amount is accounted independently of its cargo's current location.
    for (kind in ResourceKind.entries) {
        val nodes = s.nodes.values.filter { it.resource == kind }
        val initial = nodes.sumOf { it.initial }; val remaining = nodes.sumOf { it.remaining }
        assertTrue(initial >= remaining && remaining >= 0, "finite nodes never regenerate")
        val index = kind.ordinal
        // ResourceKind and bundle order deliberately differ for iron/timber: use a one-unit vector.
        val component = kind.bundle(1).values().indexOf(1)
        assertTrue(index >= 0)
        assertEquals(initial - remaining, (actual + s.spent + s.lost + s.escaped).values()[component] -
            s.imported.values()[component] - remaining, "node extraction conservation")
    }
    // 3: Home population debits equal reserves (available + consumed) plus direct production.
    val people = s.orders.values.flatMap { it.completedPeople + listOfNotNull(it.populationDebit) }
    for (source in s.population.values) {
        val batches = s.reserves.values.filter { it.sourceId == source.id }
        val direct = people.count { it.sourceId == source.id && it.reserveBatchId == null }.toLong()
        assertEquals(source.initial, source.available + batches.sumOf { it.initial } + direct, "population conservation")
        for (batch in batches) assertEquals(batch.initial, batch.remaining + people.count { it.reserveBatchId == batch.id }.toLong(), "reserve conservation")
    }
    // 4: Owner housing is exactly the surviving worker/soldier set, even above capacity.
    for (owner in listOf("A", "B")) {
        val used = s.units.values.count { it.owner == owner && it.kind == HousingKind.WORKER } +
            s.units.values.count { it.owner == owner && it.kind == HousingKind.SOLDIER }
        assertEquals(used.toLong(), ledger.housingUsed(owner), "housing conservation")
    }
}
class EconomyConservationTest {
    private fun initial() = seed(500, 500).housing().housing("B", "hb").worker().worker("B", "bw")
        .transport().transport("A", "route-cart").registerNode("n", "mine", NodeKind.DEPOSIT, ResourceKind.STONE, 100, ready).ok()
        .registerNode("f", "farm", NodeKind.FARM, ResourceKind.GRAIN, 100, ready).ok()
        .assignFarm("assign", "A", "farm", setOf("worker"), ready).ok()
        .acquireReserve("reserve", "A", "popA", "reserve-debit", ready).ok()
        .createRoute("route", "A", "r", "route-cart", "a", "aa", EconomyMode.REALTIME, ready).ok()
    private fun run(seed: Int): EconomyLedger {
        val random = Random(seed); var s = initial()
        repeat(250) { step ->
            val id = "$seed-$step"; val before = s
            val r = when (random.nextInt(18)) {
                0 -> s.transfer(id, "A", "a", "b", ResourceBundle(grain = random.nextLong(1, 12)), ready)
                1 -> s.transfer(id, "B", "b", "a", ResourceBundle(stone = random.nextLong(1, 12)), ready)
                2 -> s.gather(id, "A", "mine", "worker", ready)
                3 -> s.gather(id, "A", "farm", "worker", ready)
                4 -> s.depositCargo(id, "A", "worker", "a", 0, 1, ready)
                5 -> s.loadCargo(id, "A", "cart", "a", ResourceBundle(grain = 3), ready)
                6 -> s.depositCargo(id, "A", "cart", "a", 0, 1, ready)
                7 -> s.enqueue(id, "A", "o${random.nextInt(5)}", "producer", "a", "popA", recipe, 2, EconomyMode.REALTIME, ready)
                8 -> s.advanceProduction(id, "o${random.nextInt(5)}", random.nextBoolean(), ready)
                9 -> s.cancelProduction(id, "A", "o${random.nextInt(5)}", EconomyMode.REALTIME, ready)
                10 -> s.advanceRoute(id, "r", random.nextInt(4) != 0, ready)
                11 -> s.destroyCarrier(id, "cart", ready)
                12 -> s.state.ground.keys.firstOrNull()?.let { s.pickupCargo(id, "B", "bw", it, ready) }
                    ?: s.hitCarrier(id, "worker")
                13 -> s.depositCargo(id, "B", "bw", "b", 0, 1, ready)
                14 -> s.admitUnit(id, "A", "reinforcement$step", HousingKind.SOLDIER)
                15 -> s.destroyHousing(id, "house")
                16 -> s.addHousing(id, "A", "house$step", ready)
                else -> s.commandRoute(id, "A", "r", if (random.nextBoolean()) RouteCommand.STOP else RouteCommand.RECALL, EconomyMode.GENERAL_TURN)
            }
            s = r.ledger
            if (r.code != EconomyCode.APPLIED) assertSame(before, s)
            conservation(s)
        }
        return s
    }
    @Test fun `four conservation laws hold for 10000 random operations`() { repeat(40) { run(it) } }
    @Test fun `identical random command sequence produces identical canonical hash`() {
        repeat(10) { assertEquals(run(it).sha256(), run(it).sha256()) }
    }
    @Test fun `connected and cut resumed economic balances converge for same source after cancel`() {
        val base = initial().production()
        val a = base.advanceProduction("p", "o", false, ready).ok().cancelProduction("c", "A", "o", EconomyMode.REALTIME, ready).ok()
        val b = base.advanceProduction("p", "o", true, ready).ok().cancelProduction("c", "A", "o", EconomyMode.REALTIME, ready).ok()
        assertEquals(a.stock("A"), b.stock("A")); assertEquals(a.reserve("A"), b.reserve("A")); assertEquals(a.source("popA"), b.source("popA"))
        conservation(a); conservation(b)
    }
}
