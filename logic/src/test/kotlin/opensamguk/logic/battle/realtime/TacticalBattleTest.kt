package opensamguk.logic.battle.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TacticalBattleTest {
    private val plain = Battlefield(192, "FIELD", List(64) { "P".repeat(64) })

    private fun retinue(id: Int, strength: Int, leadership: Int, troops: Int = 100,
                        kind: UnitKind = UnitKind.INFANTRY, present: Boolean = true): Retinue =
        Retinue(id, GeneralStats(id, leadership, strength, 50, 50, 50), troops, kind,
            training = 50, morale = 100, fatigue = 0, supply = 100, accompaniesCorps = present)

    private fun state(humans: Set<BattleSide> = setOf(BattleSide.ATTACKER, BattleSide.DEFENDER)): TacticalState {
        val attacker = BattleDeployment.default(BattleSide.ATTACKER, 1, listOf(retinue(1, 90, 80)))
        val defender = BattleDeployment.default(BattleSide.DEFENDER, 2, listOf(retinue(2, 70, 70)))
        return TacticalBattle.start(17, plain, attacker, defender, humans)
    }

    @Test
    fun `commander takes center and only present generals with actual troops take slots`() {
        val retinues = listOf(retinue(1, 60, 60), retinue(2, 95, 40), retinue(3, 70, 90),
            retinue(4, 80, 70), retinue(5, 100, 100, troops = 0), retinue(6, 99, 99, present = false))
        val result = BattleDeployment.default(BattleSide.ATTACKER, 1, retinues)
        assertEquals(1, result.slots[FormationSlot.CENTER]?.general?.id)
        assertEquals(2, result.slots[FormationSlot.VANGUARD]?.general?.id)
        assertEquals(3, result.slots[FormationSlot.LEFT_WING]?.general?.id)
        assertEquals(4, result.slots[FormationSlot.RIGHT_WING]?.general?.id)
        assertEquals(4, result.slots.size)
        val changed = result.move(FormationSlot.LEFT_WING, FormationSlot.RIGHT_GUARD)
        assertEquals(3, changed.slots[FormationSlot.RIGHT_GUARD]?.general?.id)
        assertTrue(FormationSlot.LEFT_WING !in changed.slots)
        assertEquals(result, result.move(FormationSlot.CENTER, FormationSlot.CENTER))
    }

    @Test
    fun `same input log replays to exact hash and changing an order is detected`() {
        val initial = state()
        val commands = listOf(
            TacticalCommand(0, 2, BattleSide.DEFENDER, null, BattleOrder.DEFEND),
            TacticalCommand(0, 1, BattleSide.ATTACKER, null, BattleOrder.CHARGE),
        )
        val first = TacticalBattle.replay(initial, commands, 180)
        val reordered = TacticalBattle.replay(initial, commands.reversed(), 180)
        assertEquals(TacticalBattle.stateHash(first), TacticalBattle.stateHash(reordered))
        val changed = TacticalBattle.replay(initial,
            commands.map { if (it.side == BattleSide.ATTACKER) it.copy(order = BattleOrder.DEFEND) else it }, 180)
        assertNotEquals(TacticalBattle.stateHash(first), TacticalBattle.stateHash(changed))
    }

    @Test
    fun `two units contesting one tile use fixed priority and cannot exchange occupied cells`() {
        val first = TacticalUnit(BattleSide.ATTACKER, FormationSlot.CENTER, retinue(1, 80, 80,
            kind = UnitKind.CAVALRY), row = 10, col = 10, order = BattleOrder.ATTACK)
        val second = TacticalUnit(BattleSide.DEFENDER, FormationSlot.CENTER, retinue(2, 80, 80),
            row = 10, col = 12, order = BattleOrder.ATTACK)
        val current = TacticalState(1, plain, 0, listOf(first, second), setOf(BattleSide.ATTACKER, BattleSide.DEFENDER))
        val next = TacticalBattle.step(current).state
        assertEquals(11, next.units.first { it.retinue.id == 1 }.col)
        assertEquals(12, next.units.first { it.retinue.id == 2 }.col)
        val adjacent = current.copy(units = listOf(first, second.copy(col = 11)))
        val occupied = TacticalBattle.step(adjacent).state
        assertEquals(10, occupied.units.first { it.retinue.id == 1 }.col)
    }

    @Test
    fun `human can hand control to AI and nonparticipant commands are rejected`() {
        val initial = state(setOf(BattleSide.ATTACKER))
        assertFailsWith<IllegalArgumentException> {
            TacticalBattle.step(initial, listOf(TacticalCommand(0, 0, BattleSide.DEFENDER, null, BattleOrder.CHARGE)))
        }
        val humanStep = TacticalBattle.step(initial,
            listOf(TacticalCommand(0, 1, BattleSide.ATTACKER, null, BattleOrder.CHARGE))).state
        assertEquals(BattleOrder.CHARGE, humanStep.units.first { it.side == BattleSide.ATTACKER }.order)
        val aiStep = TacticalBattle.step(humanStep.copy(humanSides = emptySet())).state
        assertEquals(BattleOrder.FORMATION, aiStep.units.first { it.side == BattleSide.ATTACKER }.order)
        val reclaimed = TacticalBattle.step(aiStep.copy(humanSides = setOf(BattleSide.ATTACKER)),
            listOf(TacticalCommand(2, 2, BattleSide.ATTACKER, null, BattleOrder.DEFEND))).state
        assertEquals(BattleOrder.DEFEND, reclaimed.units.first { it.side == BattleSide.ATTACKER }.order)
    }

    @Test
    fun `wall blocks fire and a gate remains passable only after destruction`() {
        val wallRows = List(64) { row -> if (row == 10) "P".repeat(11) + "W" + "P".repeat(52) else "P".repeat(64) }
        val field = Battlefield(0, "FORTRESS", wallRows)
        val attacker = TacticalUnit(BattleSide.ATTACKER, FormationSlot.CENTER,
            retinue(1, 80, 80, kind = UnitKind.ARCHER),
            row = 10, col = 10, order = BattleOrder.ATTACK)
        val defender = TacticalUnit(BattleSide.DEFENDER, FormationSlot.CENTER, retinue(2, 80, 80),
            row = 10, col = 12, order = BattleOrder.DEFEND)
        val start = TacticalState(1, field, 0, listOf(attacker, defender),
            setOf(BattleSide.ATTACKER, BattleSide.DEFENDER), gateRow = 10, gateCol = 11, gateHp = 2)
        val first = TacticalBattle.step(start)
        assertEquals(0, first.state.gateHp)
        assertTrue(first.events.any { it.kind == TacticalEventKind.GATE_DAMAGE })
        assertEquals(100, first.state.units.first { it.retinue.id == 2 }.troops)
        val after = TacticalBattle.replay(first.state, emptyList(), 15)
        assertEquals(10, after.units.first { it.retinue.id == 1 }.col)
        assertTrue(after.units.first { it.retinue.id == 2 }.troops < 100)
    }

    @Test
    fun `river defense penalty increases incoming damage`() {
        val riverRows = List(64) { row ->
            if (row == 10) "P".repeat(11) + "R" + "P".repeat(52) else "P".repeat(64)
        }
        val attacker = TacticalUnit(BattleSide.ATTACKER, FormationSlot.CENTER,
            retinue(1, 80, 80, troops = 1000), row = 10, col = 10, order = BattleOrder.DEFEND)
        val defender = TacticalUnit(BattleSide.DEFENDER, FormationSlot.CENTER,
            retinue(2, 80, 80, troops = 1000), row = 10, col = 11, order = BattleOrder.DEFEND)
        fun remaining(field: Battlefield) = TacticalBattle.step(TacticalState(1, field, 0,
            listOf(attacker, defender), setOf(BattleSide.ATTACKER, BattleSide.DEFENDER))).state
            .units.first { it.retinue.id == 2 }.troops
        assertTrue(remaining(Battlefield(193, "FIELD", riverRows)) < remaining(plain))
    }

    @Test
    fun `attacker paths around terrain to the gate and gate coordinates affect state hash`() {
        val rows = List(64) { row -> if (row in 8..12) "P".repeat(11) + "W" + "P".repeat(52) else "P".repeat(64) }
        val field = Battlefield(0, "FORTRESS", rows)
        val attacker = TacticalUnit(BattleSide.ATTACKER, FormationSlot.CENTER, retinue(1, 80, 80),
            row = 9, col = 9, order = BattleOrder.ATTACK)
        val defender = TacticalUnit(BattleSide.DEFENDER, FormationSlot.CENTER, retinue(2, 80, 80),
            row = 10, col = 13, order = BattleOrder.DEFEND)
        val start = TacticalState(1, field, 0, listOf(attacker, defender),
            setOf(BattleSide.ATTACKER, BattleSide.DEFENDER), gateRow = 10, gateCol = 11, gateHp = 2)
        assertNotEquals(TacticalBattle.stateHash(start), TacticalBattle.stateHash(start.copy(gateRow = 9)))
        val after = TacticalBattle.replay(start, emptyList(), 30)
        assertEquals(0, after.gateHp)
        assertTrue(after.units.first { it.retinue.id == 1 }.col >= 11)
    }
}
