package opensamguk.logic.battle.realtime.soldier

import kotlin.test.*
import opensamguk.logic.battle.realtime.*
import opensamguk.logic.battle.realtime.space.*

class TacticalV2BattleTest {
    @Test
    fun `each actual source gets one soldier per troop and sorted nonreused IDs`() {
        val state = ready(fixture(troops = 3))
        assertEquals((1L..6L).toList(), state.soldiers.map { it.id })
        state.context.facts.forEach { fact ->
            val soldiers = state.soldiers.filter { it.sourceKey == fact.source.key }
            assertEquals(3, soldiers.size)
            assertTrue(soldiers.all { it.pos.cell() in state.context.deployment.allowedCells.getValue(fact.source.side) })
        }
        assertBalances(state)
    }

    @Test
    fun `capacity refusal never silently drops troops`() {
        val context = fixture(troops = 5, singleSpawn = true)
        assertEquals("SPACE_CAPACITY_EXCEEDED", assertIs<TacticalV2BattleStart.Rejected>(
            TacticalV2Battle.start(context, fixtureFactory)).reason)
    }

    @Test
    fun `foreign unknown duplicate and mixed selections reject atomically`() {
        val state = ready(fixture())
        val mine = state.soldiers.first()
        val enemy = state.soldiers.last()
        val controlled = setOf(mine.sourceKey)
        for (selection in listOf(listOf(mine.id, enemy.id), listOf(mine.id, 999L), listOf(mine.id, mine.id))) {
            val decision = TacticalV2Battle.command(state, listOf(TacticalV2SoldierCommand(selection,
                TacticalV2SoldierOrder.Move(TacticalV2Cell(20, 20)))), controlled)
            assertFalse(decision.accepted)
            assertSame(state, decision.state)
            assertEquals(state.stateSha256(), decision.state.stateSha256())
        }
    }

    @Test
    fun `stationary garrison cannot move or retreat by command`() {
        val state = ready(fixture(garrison = true))
        val guard = state.soldiers.last()
        for (order in listOf(TacticalV2SoldierOrder.Move(TacticalV2Cell(20, 20)),
            TacticalV2SoldierOrder.Retreat)) {
            val decision = TacticalV2Battle.command(state, listOf(TacticalV2SoldierCommand(listOf(guard.id), order)),
                setOf(guard.sourceKey))
            assertFalse(decision.accepted)
            assertSame(state, decision.state)
        }
    }

    @Test
    fun `stationary garrison can target an enemy but never follows it`() {
        val initial = ready(fixture(garrison = true))
        val guard = initial.soldiers.last()
        val ordered = issue(initial, guard.id, TacticalV2SoldierOrder.Attack(1))
        val after = TacticalV2Battle.step(ordered, fixtureFactory)
        assertEquals(guard.pos, after.soldiers.last().pos)
        assertEquals(1L, after.soldiers.last().targetId)
    }

    @Test
    fun `stop is authorized and cancels movement without changing position`() {
        val initial = ready(fixture())
        val mine = initial.soldiers.first()
        val moving = issue(initial, mine.id, TacticalV2SoldierOrder.Move(TacticalV2Cell(20, 20)))
        val stopped = issue(moving, mine.id, TacticalV2SoldierOrder.Stop)
        assertNull(stopped.soldiers.first().goal)
        assertEquals(mine.pos, TacticalV2Battle.step(stopped, fixtureFactory).soldiers.first().pos)
    }

    @Test
    fun `commands and selected IDs are order independent`() {
        val a = ready(fixture(troops = 2))
        val mine = a.soldiers.take(2).map { it.id }
        val x = TacticalV2SoldierCommand(mine, TacticalV2SoldierOrder.Move(TacticalV2Cell(20, 20)))
        val controlled = setOf(a.soldiers.first().sourceKey)
        assertEquals(TacticalV2Battle.command(a, listOf(x), controlled).state.stateSha256(),
            TacticalV2Battle.command(a, listOf(x.copy(soldierIds = mine.reversed())), controlled).state.stateSha256())
        val split = mine.map { TacticalV2SoldierCommand(listOf(it), x.order) }
        assertEquals(TacticalV2Battle.command(a, split, controlled).state.stateSha256(),
            TacticalV2Battle.command(a, split.reversed(), controlled).state.stateSha256())
    }

    @Test
    fun `movement is applied in ascending soldier ID and updates canonical positions`() {
        val moves = mutableListOf<Long>()
        val factory = TacticalV2SoldierSpaceFactory { field, gate, params -> FixtureSpace(field, gate, params, moves) }
        val state = ready(fixture(troops = 2))
        val moving = TacticalV2Battle.command(state, listOf(TacticalV2SoldierCommand(listOf(2, 1),
            TacticalV2SoldierOrder.Move(TacticalV2Cell(20, 20)))), setOf(state.soldiers.first().sourceKey)).state
        val next = TacticalV2Battle.step(moving, factory)
        assertEquals(listOf(1L, 2L), moves)
        assertNotEquals(moving.soldiers.first().pos, next.soldiers.first().pos)
        val rebuilt = TacticalV2Battle.rebuildSpace(next, factory)
        next.soldiers.filter { it.alive }.forEach { assertEquals(it.pos, rebuilt.positionOf(it.id)) }
    }

    @Test
    fun `HP zero means incapacitated and exactly one casualty never a general death`() {
        val state = ready(fixture(adjacent = true, hp = 1, troops = 2))
        val next = TacticalV2Battle.step(state, fixtureFactory)
        assertTrue(next.soldiers.any { !it.alive })
        assertBalances(next)
        next.context.facts.forEach { fact -> assertEquals(fact.general,
            state.context.facts.single { it.source.key == fact.source.key }.general) }
        val repeat = TacticalV2Battle.step(next, fixtureFactory)
        assertBalances(repeat)
        next.soldiers.filter { !it.alive }.forEach { casualty ->
            assertEquals(casualty.id, repeat.soldiers.single { it.id == casualty.id }.id)
            assertFalse(TacticalV2Battle.command(next, listOf(TacticalV2SoldierCommand(listOf(casualty.id),
                TacticalV2SoldierOrder.Stop)), setOf(casualty.sourceKey)).accepted)
        }
    }

    @Test
    fun `attack interval prevents damage on intervening ticks`() {
        val state = ready(fixture(adjacent = true, hp = 100))
        val hit = TacticalV2Battle.step(state, fixtureFactory)
        assertTrue(hit.soldiers.all { it.hp < 100 })
        val waiting = TacticalV2Battle.step(hit, fixtureFactory)
        assertEquals(hit.soldiers.map { it.hp }, waiting.soldiers.map { it.hp })
        assertEquals(state.context.rules.attackIntervalTicks - 1, waiting.soldiers.first().attackWait)
    }

    @Test
    fun `retreat and surrender remain undecided and never escape entire source`() {
        val state = ready(fixture())
        for (order in listOf(TacticalV2SoldierOrder.Retreat, TacticalV2SoldierOrder.Surrender)) {
            val decision = TacticalV2Battle.command(state, listOf(TacticalV2SoldierCommand(listOf(1), order)),
                setOf(state.soldiers.first().sourceKey))
            assertFalse(decision.accepted)
            assertTrue(requireNotNull(decision.reason).startsWith("NOT_DECIDED"))
            assertSame(state, decision.state)
            assertTrue(decision.state.ledger.balances.values.all { it.escaped == 0 })
        }
    }

    @Test
    fun `execution budget is not victory and exhausted state cannot accept commands`() {
        val state = TacticalV2Battle.runForTest(ready(fixture(budget = 3)), fixtureFactory)
        assertEquals(3L, state.tick)
        assertEquals(TacticalV2SoldierRunStatus.BUDGET_EXHAUSTED, state.status)
        assertTrue(TacticalV2Battle.victoryDecision(state).startsWith("NOT_DECIDED"))
        assertSame(state, TacticalV2Battle.step(state, fixtureFactory))
        assertFalse(TacticalV2Battle.command(state, emptyList(), emptySet()).accepted)
    }

    @Test
    fun `v1 tick limit does not terminate soldier battle and finite test budget is mandatory`() {
        val context = fixture(budget = null, rules = TacticalRules.CANON.copy(battleTicks = 1))
        var state = ready(context)
        repeat(4) { state = TacticalV2Battle.step(state, fixtureFactory) }
        assertEquals(4L, state.tick)
        assertEquals(TacticalV2SoldierRunStatus.RUNNING, state.status)
        assertFailsWith<IllegalArgumentException> { TacticalV2Battle.runForTest(state, fixtureFactory) }
    }

    @Test
    fun `wall blocks arrows and destruction opens only pinned gate`() {
        val rows = MutableList(64) { "P".repeat(64) }
        rows[10] = rows[10].replaceRange(11, 12, "W")
        val gate = TacticalV2Cell(10, 11)
        val blocked = ready(fixture(rows = rows, gate = gate, gateHp = 2, archers = true))
        val next = TacticalV2Battle.step(blocked, fixtureFactory)
        assertEquals(blocked.soldiers.map { it.hp }, next.soldiers.map { it.hp })
        // Place one attacker immediately left of the gate; gate damage is independent of victory.
        val atGate = blocked.evolve(soldiers = blocked.soldiers.map { soldier ->
            if (soldier.id == 1L) soldier.copy(pos = TacticalV2Pos(Fx(10 * Fx.ONE + Fx.ONE / 2),
                Fx(10 * Fx.ONE + Fx.ONE * 3 / 4))) else soldier
        })
        val opened = TacticalV2Battle.step(atGate, fixtureFactory)
        assertEquals(0, opened.gateHp)
        assertTrue(TacticalV2Battle.rebuildSpace(opened, fixtureFactory).passable(cellCenter(gate)))
        assertTrue(TacticalV2Battle.victoryDecision(opened).startsWith("NOT_DECIDED"))
    }

    @Test
    fun `pure AI and stateless hash rolls are repeatable`() {
        val state = ready(fixture())
        val hash = state.stateSha256()
        assertEquals(TacticalV2Ai.orders(state, BattleSide.ATTACKER), TacticalV2Ai.orders(state, BattleSide.ATTACKER))
        assertEquals(hash, state.stateSha256())
        val a = TacticalV2Ai.roll(7, 1, 1, "attack")
        TacticalV2Ai.roll(7, 1, 99, "attack")
        assertEquals(a, TacticalV2Ai.roll(7, 1, 1, "attack"))
        assertNotEquals(a, TacticalV2Ai.roll(7, 1, 1, "other"))
    }

    @Test
    fun `shuffled committed fact inputs produce same full run hash`() {
        val a = fixture(budget = 10)
        val b = TacticalV2BattleContext(a.seed, a.battlefield, a.deployment, a.facts.reversed(), a.rules,
            a.combatParams, a.spaceParams, a.humanSides, runBudgetTicks = a.runBudgetTicks)
        assertEquals(TacticalV2Battle.runForTest(ready(a), fixtureFactory).stateSha256(),
            TacticalV2Battle.runForTest(ready(b), fixtureFactory).stateSha256())
    }

    @Test
    fun `mutable caller lists cannot change state facts`() {
        val original = fixture()
        val facts = original.facts.toMutableList()
        val rows = original.battlefield.rows.toMutableList()
        val context = TacticalV2BattleContext(original.seed, original.battlefield.copy(rows = rows),
            original.deployment, facts, original.rules, original.combatParams, original.spaceParams,
            original.humanSides)
        val before = context.sha256
        rows[0] = "M".repeat(64)
        facts.clear()
        assertEquals(before, context.sha256)
        assertEquals(2, context.facts.size)
        assertEquals("P".repeat(64), context.battlefield.rows[0])
    }

    @Test
    fun `unconfirmed parameter decisions reject start before constructing context`() {
        val context = fixture()
        val combat = TacticalV2SoldierCombatParamsLoad.Ready(context.combatParams)
        val space = TacticalV2SpaceParamsLoad.Ready(context.spaceParams)
        val notCombat = TacticalV2SoldierCombatParamsLoad.NotDecided("PROPOSED")
        val notSpace = TacticalV2SpaceParamsLoad.NotDecided("PROPOSED")
        for ((c, s) in listOf(notCombat to space, combat to notSpace)) {
            assertTrue(assertIs<TacticalV2BattleStart.Rejected>(TacticalV2Battle.start(c, s,
                { _, _ -> error("unconfirmed context must not be constructed") }, fixtureFactory)).reason.startsWith("NOT_DECIDED"))
        }
    }

    @Test
    fun `automatic NPC or disconnected defense never starts idle offensive movement`() {
        val c = fixture()
        val context = TacticalV2BattleContext(c.seed, c.battlefield, c.deployment, c.facts, c.rules,
            c.combatParams, c.spaceParams, emptySet(), runBudgetTicks = 3)
        val initial = ready(context)
        val after = TacticalV2Battle.runForTest(initial, fixtureFactory)
        assertEquals(initial.soldiers.map { it.pos }, after.soldiers.map { it.pos })
        assertTrue(TacticalV2Ai.orders(initial, BattleSide.ATTACKER).all { it.order == TacticalV2SoldierOrder.Stop })
    }

    private fun assertBalances(state: TacticalV2BattleState) {
        state.context.facts.forEach { fact ->
            val balance = state.ledger.balances.getValue(fact.source.key)
            assertEquals(balance.active, state.soldiers.count { it.sourceKey == fact.source.key && it.alive })
            assertEquals(balance.initial, balance.active + balance.escaped + balance.casualties)
        }
    }
}

internal fun ready(context: TacticalV2BattleContext): TacticalV2BattleState =
    assertIs<TacticalV2BattleStart.Ready>(TacticalV2Battle.start(context, fixtureFactory)).state

internal fun issue(state: TacticalV2BattleState, id: Long, order: TacticalV2SoldierOrder): TacticalV2BattleState =
    TacticalV2Battle.command(state, listOf(TacticalV2SoldierCommand(listOf(id), order)),
        setOf(state.soldiers.single { it.id == id }.sourceKey)).also { assertTrue(it.accepted) }.state

internal fun fixture(troops: Int = 1, singleSpawn: Boolean = false, adjacent: Boolean = false,
                     garrison: Boolean = false, hp: Int = 10, budget: Long? = 40,
                     rows: List<String> = List(64) { "P".repeat(64) }, gate: TacticalV2Cell? = null,
                     gateHp: Int = 0, archers: Boolean = false,
                     rules: TacticalRules = TacticalRules.CANON): TacticalV2BattleContext {
    val attacker = TacticalV2UnitSource(TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "1"),
        BattleSide.ATTACKER, 1, 1, troops, 1)
    val defender = TacticalV2UnitSource(if (garrison)
        TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "guard", 1)
        else TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "2"),
        BattleSide.DEFENDER, if (garrison) null else 2, if (garrison) null else 2, troops, 1)
    val a = TacticalV2Cell(10, 10)
    val d = TacticalV2Cell(10, if (adjacent) 11 else 12)
    val allowed = mapOf(BattleSide.ATTACKER to if (singleSpawn) setOf(a) else
        (8..10).flatMap { r -> (8..10).map { c -> TacticalV2Cell(r, c) } }.toSet(),
        BattleSide.DEFENDER to if (singleSpawn) setOf(d) else
        (10..12).flatMap { r -> (d.col..14).map { c -> TacticalV2Cell(r, c) } }.toSet())
    val deployment = TacticalV2Deployment(0, "a".repeat(64), listOf(attacker, defender),
        mapOf(attacker.key to a, defender.key to d), allowed)
    val facts = listOf(attacker, defender).map { source -> TacticalV2SoldierSourceFacts(source, 1,
        if (archers) UnitKind.ARCHER else UnitKind.INFANTRY,
        source.commanderGeneralId?.let { GeneralStats(it, 50, 50, 50, 50, 50) }, 50, 100, 0, 100) }
    return TacticalV2BattleContext(7, Battlefield(0, if (rows.any { 'W' in it }) "FORTRESS" else "FIELD", rows), deployment, facts, rules,
        TacticalV2SoldierCombatParams(hp, 4, "b".repeat(64)),
        TacticalV2SpaceParams(4, Fx(Fx.ONE / 8), "c".repeat(64)), BattleSide.entries.toSet(),
        gate, gateHp, budget)
}

internal val fixtureFactory = TacticalV2SoldierSpaceFactory { field, gate, params -> FixtureSpace(field, gate, params) }

/** Small open-field S0 test double, not the production C-4a-S grid/path implementation. */
private class FixtureSpace(val field: Battlefield, val gate: TacticalV2Cell?, val params: TacticalV2SpaceParams,
                           val moves: MutableList<Long> = mutableListOf()) : TacticalV2Space {
    private val positions = linkedMapOf<Long, TacticalV2Pos>()
    private var open = false
    override fun passable(pos: TacticalV2Pos): Boolean {
        if (pos.row.raw !in 0 until 64 * Fx.ONE || pos.col.raw !in 0 until 64 * Fx.ONE) return false
        val cell = pos.cell()
        return when (field.at(cell.row, cell.col)) {
            'P', 'F', 'R' -> true
            'W' -> cell == gate && open
            else -> false
        }
    }
    override fun setGateOpen(cell: TacticalV2Cell, open: Boolean) { require(cell == gate); this.open = open }
    override fun canOccupy(pos: TacticalV2Pos, ignore: Long?): Boolean = passable(pos) &&
        positions.filterKeys { it != ignore }.values.count { it.cell() == pos.cell() } < params.cellCapacity &&
        positions.filterKeys { it != ignore }.values.none {
            distanceSquared(pos, it) < 4 * params.soldierRadius.raw * params.soldierRadius.raw }
    override fun neighbors(pos: TacticalV2Pos, range: Fx): List<Long> = positions.filterValues {
        distanceSquared(pos, it) <= range.raw * range.raw }.keys.sorted()
    override fun positionOf(id: Long): TacticalV2Pos? = positions[id]
    override fun place(id: Long, pos: TacticalV2Pos) { require(id !in positions && canOccupy(pos, null)); positions[id] = pos }
    override fun move(id: Long, to: TacticalV2Pos) { require(id in positions && canOccupy(to, id)); positions[id] = to; moves += id }
    override fun remove(id: Long) { positions.remove(id) }
    override fun nextStep(id: Long, goal: TacticalV2Cell, speed: Fx): TacticalV2Pos {
        val start = positions.getValue(id)
        val target = cellCenter(goal)
        fun delta(value: Long) = value.coerceIn(-speed.raw, speed.raw)
        val next = if (start.row != target.row) start.copy(row = Fx(start.row.raw + delta(target.row.raw - start.row.raw)))
            else start.copy(col = Fx(start.col.raw + delta(target.col.raw - start.col.raw)))
        return if (canOccupy(next, id)) next else start
    }
}
