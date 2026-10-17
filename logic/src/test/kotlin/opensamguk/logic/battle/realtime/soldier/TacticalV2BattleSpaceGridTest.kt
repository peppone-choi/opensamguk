package opensamguk.logic.battle.realtime.soldier

import kotlin.test.*
import opensamguk.logic.battle.realtime.*
import opensamguk.logic.battle.realtime.space.*

class TacticalV2BattleSpaceGridTest {
    @Test
    fun `infantry forest cost is applied once on entry within and exit`() = terrainSteps(UnitKind.INFANTRY, 'F')

    @Test
    fun `infantry river cost is applied once on entry within and exit`() = terrainSteps(UnitKind.INFANTRY, 'R')

    @Test
    fun `archer forest cost is applied once on entry within and exit`() = terrainSteps(UnitKind.ARCHER, 'F')

    @Test
    fun `archer river cost is applied once on entry within and exit`() = terrainSteps(UnitKind.ARCHER, 'R')

    @Test
    fun `cavalry forest cost is applied once on entry within and exit`() = terrainSteps(UnitKind.CAVALRY, 'F')

    @Test
    fun `cavalry river cost is applied once on entry within and exit`() = terrainSteps(UnitKind.CAVALRY, 'R')

    private fun terrainSteps(kind: UnitKind, terrain: Char) {
        val rules = TacticalRules.CANON
        val rows = corridor(terrain)
        val initial = start(context(rows = rows, kind = kind))
        val ticks = ticks(kind, rules)
        val extra = if (terrain == 'F') rules.forestExtraTicks else rules.riverExtraTicks
        val cases = listOf(
            Triple(centre(10, 10), TacticalV2Cell(10, 12), extra),
            Triple(centre(10, 11).copy(col = Fx(11 * Fx.ONE + Fx.ONE / 4)), TacticalV2Cell(10, 11), extra),
            Triple(centre(10, 11), TacticalV2Cell(10, 12), 0),
        )
        for ((from, goal, cost) in cases) {
            val state = initial.evolve(soldiers = initial.soldiers.map {
                if (it.id == 1L) it.copy(pos = from) else it
            })
            val after = TacticalV2Battle.step(issue(state, 1, TacticalV2SoldierOrder.Move(goal)),
                TacticalV2Battle.spaceGrid(state.context.rules))
            assertEquals(from.row, after.soldiers.first().pos.row)
            assertEquals(Fx.ONE / (ticks + cost), after.soldiers.first().pos.col.raw - from.col.raw,
                "$kind $terrain from=$from goal=$goal")
        }
    }

    @Test
    fun `factory forwards noncanonical context rules instead of using grid defaults`() {
        // Fixture-only values do not confirm the proposed product soldier/space parameters.
        val rules = TacticalRules.CANON.copy(forestExtraTicks = TacticalRules.CANON.forestExtraTicks + 7)
        val initial = start(context(rows = corridor('F'), rules = rules))
        val moving = issue(initial, 1, TacticalV2SoldierOrder.Move(TacticalV2Cell(10, 12)))
        val from = moving.soldiers.first().pos
        val expected = Fx.ONE / (rules.infantryMoveTicks + rules.forestExtraTicks)
        val factory = TacticalV2Battle.spaceGrid(moving.context.rules)
        assertIs<TacticalV2SpaceGrid>(factory.create(moving.context.battlefield, null, moving.context.spaceParams))
        val next = TacticalV2Battle.step(moving, factory)
        assertEquals(expected, next.soldiers.first().pos.col.raw - from.col.raw)
        // Negative control: omitting rules really changes this regression's one-tick result.
        val defaults = TacticalV2SoldierSpaceFactory { field, gate, params -> TacticalV2SpaceGrid(field, params, gate) }
        assertNotEquals(expected, TacticalV2Battle.step(moving, defaults).soldiers.first().pos.col.raw - from.col.raw)
    }

    @Test
    fun `real grid runs a finite mixed battle and preserves source balances each tick`() {
        val context = fixture(troops = 3, hp = 100, budget = 60)
        val mixed = TacticalV2BattleContext(context.seed, context.battlefield, context.deployment,
            context.facts.mapIndexed { index, fact -> fact.copy(kind = if (index == 0) UnitKind.INFANTRY else UnitKind.CAVALRY) },
            context.rules, context.combatParams, context.spaceParams, context.humanSides, runBudgetTicks = context.runBudgetTicks)
        val factory = TacticalV2Battle.spaceGrid(mixed.rules)
        val ready = start(mixed)
        val commands = ready.soldiers.map { soldier -> TacticalV2SoldierCommand(listOf(soldier.id),
            TacticalV2SoldierOrder.Move(TacticalV2Cell(10, 11))) }
        val decision = TacticalV2Battle.command(ready, commands, mixed.facts.map { it.source.key }.toSet())
        assertTrue(decision.accepted)
        val initial = decision.state
        var state = initial
        while (state.status == TacticalV2SoldierRunStatus.RUNNING) {
            assertBalances(state)
            state = TacticalV2Battle.step(state, factory)
        }
        assertBalances(state)
        assertNotEquals(initial.soldiers.map { it.pos }, state.soldiers.map { it.pos })
        assertEquals(60L, state.tick)
        assertEquals(TacticalV2SoldierRunStatus.BUDGET_EXHAUSTED, state.status)
        assertEquals(state.stateSha256(), TacticalV2Battle.runForTest(initial, factory).stateSha256())
    }

    @Test
    fun `mixed field execution is deterministic including shuffled source facts`() {
        val context = context(budget = 40)
        fun hashes(facts: List<TacticalV2SoldierSourceFacts>): List<String> {
            val c = TacticalV2BattleContext(context.seed, context.battlefield, context.deployment, facts,
                context.rules, context.combatParams, context.spaceParams, context.humanSides, runBudgetTicks = 40)
            var state = issue(start(c), 1, TacticalV2SoldierOrder.Move(TacticalV2Cell(10, 14)))
            state = issue(state, 2, TacticalV2SoldierOrder.Move(TacticalV2Cell(20, 24)))
            val hashes = mutableListOf(state.stateSha256())
            repeat(40) {
                state = TacticalV2Battle.step(state, TacticalV2Battle.spaceGrid(c.rules))
                assertBalances(state)
                hashes += state.stateSha256()
            }
            return hashes
        }
        val baseline = hashes(context.facts)
        assertEquals(baseline, hashes(context.facts))
        assertEquals(baseline, hashes(context.facts.reversed()))
        assertNotEquals(baseline.first(), baseline.last())
    }

    @Test
    fun `fortress wall remains impassable until pinned gate is destroyed then soldiers cross`() {
        val gate = TacticalV2Cell(10, 11)
        val rows = List(64) { "P".repeat(11) + "W" + "P".repeat(52) }
        val context = context(rows = rows, gate = gate,
            gateHp = TacticalRules.CANON.gateDamagePerAttack * 2, budget = 100)
        val factory = TacticalV2Battle.spaceGrid(context.rules)
        var state = issue(start(context), 1, TacticalV2SoldierOrder.Move(TacticalV2Cell(10, 14)))
        assertFalse(TacticalV2Battle.rebuildSpace(state, factory).passable(centre(10, 11)))
        var closedTicks = 0
        var crossed = false
        while (state.status == TacticalV2SoldierRunStatus.RUNNING) {
            if (state.gateHp > 0) {
                closedTicks++
                assertTrue(state.soldiers.all { context.battlefield.at(it.pos.cell().row, it.pos.cell().col) != 'W' })
            } else {
                val space = TacticalV2Battle.rebuildSpace(state, factory)
                assertTrue(space.passable(centre(10, 11)))
                assertFalse(space.passable(centre(9, 11)))
                crossed = crossed || state.soldiers.first().pos.cell().col > gate.col
            }
            state = TacticalV2Battle.step(state, factory)
            assertBalances(state)
        }
        assertTrue(closedTicks > 0)
        assertEquals(0, state.gateHp)
        assertTrue(crossed)
    }

    @Test
    fun `mountain goal uses nearest reachable replacement without entering blocked cells`() {
        val rows = List(64) { row -> CharArray(64) { col ->
            if (row == 10 && col in 10..12 || row == 20 && col == 20) 'P' else 'M'
        }.concatToString() }
        val initial = start(context(rows = rows, budget = 40))
        val factory = TacticalV2Battle.spaceGrid(initial.context.rules)
        var state = issue(initial, 1, TacticalV2SoldierOrder.Move(TacticalV2Cell(10, 14)))
        repeat(40) {
            state = TacticalV2Battle.step(state, factory)
            assertTrue(state.soldiers.all { factory.create(state.context.battlefield, null,
                state.context.spaceParams).passable(it.pos) })
            assertBalances(state)
        }
        assertEquals(centre(10, 12), state.soldiers.first().pos)
    }

    @Test
    fun `proposed loaders and undecided retreat surrender victory stay inactive with real grid`() {
        val context = context()
        val factory = TacticalV2Battle.spaceGrid(context.rules)
        val combat = TacticalV2SoldierCombatParams.load()
        val space = TacticalV2SpaceParams.load()
        assertIs<TacticalV2SoldierCombatParamsLoad.NotDecided>(combat)
        assertIs<TacticalV2SpaceParamsLoad.NotDecided>(space)
        assertEquals("NOT_DECIDED: soldier combat / space parameters", assertIs<TacticalV2BattleStart.Rejected>(
            TacticalV2Battle.start(combat, space, { _, _ -> error("PROPOSED must not construct context") }, factory)).reason)
        val initial = start(context)
        for ((order, reason) in listOf(TacticalV2SoldierOrder.Retreat to "NOT_DECIDED: exitZone / escapeOne",
            TacticalV2SoldierOrder.Surrender to "NOT_DECIDED: surrender")) {
            val decision = TacticalV2Battle.command(initial, listOf(TacticalV2SoldierCommand(listOf(1), order)),
                setOf(initial.soldiers.first().sourceKey))
            assertFalse(decision.accepted)
            assertSame(initial, decision.state)
            assertEquals(reason, decision.reason)
        }
        assertEquals("NOT_DECIDED: captureRadius / captureTicks / captureDecayPerTick / captureDecayGraceTicks / productionMeans / headquarters / victoryUnit",
            TacticalV2Battle.victoryDecision(initial))
    }

    private fun context(rows: List<String> = List(64) { "P".repeat(64) }, kind: UnitKind = UnitKind.INFANTRY,
                        rules: TacticalRules = TacticalRules.CANON, gate: TacticalV2Cell? = null,
                        gateHp: Int = 0, budget: Long = 40): TacticalV2BattleContext {
        val base = fixture(hp = 100)
        val a = TacticalV2Cell(10, 10)
        val d = TacticalV2Cell(20, 20)
        val sources = base.deployment.units
        val deployment = TacticalV2Deployment(0, base.deployment.terrainSha256, sources,
            mapOf(sources[0].key to a, sources[1].key to d),
            mapOf(BattleSide.ATTACKER to setOf(a), BattleSide.DEFENDER to setOf(d)))
        return TacticalV2BattleContext(base.seed, Battlefield(0, if (gate == null) "FIELD" else "FORTRESS", rows),
            deployment, base.facts.mapIndexed { index, fact -> fact.copy(kind = if (index == 0) kind else UnitKind.CAVALRY) },
            rules, base.combatParams, TacticalV2SpaceParams(1, Fx(Fx.ONE / 2), base.spaceParams.sha256),
            BattleSide.entries.toSet(), gate, gateHp, budget)
    }

    private fun start(context: TacticalV2BattleContext): TacticalV2BattleState =
        assertIs<TacticalV2BattleStart.Ready>(TacticalV2Battle.start(context, TacticalV2Battle.spaceGrid(context.rules))).state

    private fun corridor(terrain: Char): List<String> = List(64) { row -> CharArray(64) { col ->
        when {
            row == 10 && col in 10..12 -> if (col == 11) terrain else 'P'
            row == 20 && col == 20 -> 'P'
            else -> 'M'
        }
    }.concatToString() }

    private fun centre(row: Int, col: Int) = TacticalV2Pos(Fx(row * Fx.ONE + Fx.ONE / 2), Fx(col * Fx.ONE + Fx.ONE / 2))

    private fun ticks(kind: UnitKind, rules: TacticalRules): Int = when (kind) {
        UnitKind.INFANTRY -> rules.infantryMoveTicks
        UnitKind.ARCHER -> rules.archerMoveTicks
        UnitKind.CAVALRY -> rules.cavalryMoveTicks
    }

    private fun assertBalances(state: TacticalV2BattleState) {
        state.context.facts.forEach { fact ->
            val balance = state.ledger.balances.getValue(fact.source.key)
            assertEquals(balance.active, state.soldiers.count { it.alive && it.sourceKey == fact.source.key })
            assertEquals(balance.initial, balance.active + balance.escaped + balance.casualties)
        }
    }
}
