package opensamguk.logic.battle.realtime.soldier

import kotlin.test.*

class TacticalV2BattleCodecTest {
    @Test
    fun `checkpoint restore rebuild and continue matches uninterrupted run`() {
        var state = ready(fixture(budget = 12))
        state = issue(state, 1, TacticalV2SoldierOrder.Move(opensamguk.logic.battle.realtime.TacticalV2Cell(20, 20)))
        repeat(4) { state = TacticalV2Battle.step(state, fixtureFactory) }
        val bytes = TacticalV2BattleCodec.encode(state)
        val restored = TacticalV2BattleCodec.decode(bytes, state.context, state.stateSha256())
        assertContentEquals(bytes, TacticalV2BattleCodec.encode(restored))
        val index = TacticalV2Battle.rebuildSpace(restored, fixtureFactory)
        restored.soldiers.filter { it.alive }.forEach { assertEquals(it.pos, index.positionOf(it.id)) }
        assertEquals(TacticalV2Battle.runForTest(state, fixtureFactory).stateSha256(),
            TacticalV2Battle.runForTest(restored, fixtureFactory).stateSha256())
    }

    @Test
    fun `casualty and exhausted budget checkpoints preserve exact ledger and final hash`() {
        val final = TacticalV2Battle.runForTest(ready(fixture(adjacent = true, hp = 1, budget = 2)), fixtureFactory)
        val decoded = TacticalV2BattleCodec.decode(TacticalV2BattleCodec.encode(final), final.context, final.stateSha256())
        assertEquals(final.ledger, decoded.ledger)
        assertEquals(final.stateSha256(), decoded.stateSha256())
        assertEquals(TacticalV2SoldierRunStatus.BUDGET_EXHAUSTED, decoded.status)
    }

    @Test
    fun `wrong source combat space rule or seed pins are rejected`() {
        val state = ready(fixture())
        val bytes = TacticalV2BattleCodec.encode(state)
        val otherHp = fixture(hp = 11)
        assertFailsWith<IllegalArgumentException> { TacticalV2BattleCodec.decode(bytes, otherHp, state.stateSha256()) }
        assertFailsWith<IllegalArgumentException> { TacticalV2BattleCodec.decode(bytes,
            fixture(rules = state.context.rules.copy(infantryMoveTicks = 99)), state.stateSha256()) }
        val c = state.context
        val otherSpace = TacticalV2BattleContext(c.seed, c.battlefield, c.deployment, c.facts, c.rules, c.combatParams,
            c.spaceParams.copy(sha256 = "d".repeat(64)), c.humanSides, runBudgetTicks = c.runBudgetTicks)
        assertFailsWith<IllegalArgumentException> { TacticalV2BattleCodec.decode(bytes, otherSpace, state.stateSha256()) }
    }

    @Test
    fun `corrupt truncated trailing and unknown version bytes cannot restore`() {
        val state = ready(fixture())
        val bytes = TacticalV2BattleCodec.encode(state)
        val version = bytes.copyOf().also { it[7] = 99 }
        for (bad in listOf(version, bytes.copyOf(8), bytes + byteArrayOf(0))) {
            assertFailsWith<IllegalArgumentException> { TacticalV2BattleCodec.decode(bad, state.context, soldierSha(bad)) }
        }
        assertFailsWith<IllegalArgumentException> { TacticalV2BattleCodec.decode(bytes, state.context, "0".repeat(64)) }
    }
}
