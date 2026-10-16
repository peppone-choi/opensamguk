package opensamguk.logic.battle.realtime

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TacticalV2TroopLedgerTest {
    @Test
    fun `single escape and capture debit exactly one active soldier`() {
        val source = retinue(1, 4)
        val initial = TacticalV2TroopLedger.fromSources(listOf(source))
        val after = initial.escapeOne(source.key).captureOne(source.key).takeCasualties(source.key, 1)
        assertEquals(TacticalV2TroopBalance(4, 1, 1, 1, 1), after.balances.getValue(source.key))
        assertEquals(TacticalV2TroopBalance(4, 4, 0, 0), initial.balances.getValue(source.key))
        assertEquals(TacticalV2TroopBalance(4, 0, 2, 1, 1), after.escape(source.key).balances.getValue(source.key))
    }

    @Test
    fun `zero active escape and capture fail without changing the ledger`() {
        val source = retinue(1, 1)
        for (empty in listOf(
            TacticalV2TroopLedger.fromSources(listOf(source)).escapeOne(source.key),
            TacticalV2TroopLedger.fromSources(listOf(source)).captureOne(source.key),
            TacticalV2TroopLedger.fromSources(listOf(source)).takeCasualties(source.key, 1),
        )) {
            val before = empty.balances.toMap()
            assertFailsWith<IllegalArgumentException> { empty.escapeOne(source.key) }
            assertFailsWith<IllegalArgumentException> { empty.captureOne(source.key) }
            assertEquals(before, empty.balances)
        }
    }

    @Test
    fun `city garrison cannot escape one but can be captured and no longer counted as remaining`() {
        val source = TacticalV2UnitSource(
            TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "city-a", 9),
            BattleSide.DEFENDER, null, null, 3, 0)
        val initial = TacticalV2TroopLedger.fromSources(listOf(source))
        assertFailsWith<IllegalArgumentException> { initial.escapeOne(source.key) }
        assertFailsWith<IllegalArgumentException> { initial.escape(source.key) }
        val after = initial.captureOne(source.key).takeCasualties(source.key, 1)
        assertEquals(1, after.cityRemaining(9))
        assertEquals(TacticalV2TroopBalance(3, 1, 0, 1, 1), after.balances.getValue(source.key))
    }

    @Test
    fun `all source kinds conserve troops throughout seeded random operation sequences`() {
        val sources = listOf(retinue(1, 91),
            TacticalV2UnitSource(TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "a", 3),
                BattleSide.DEFENDER, null, null, 87, 0),
            TacticalV2UnitSource(TacticalV2SourceKey(TacticalV2SourceKind.PRODUCED, "p"),
                BattleSide.ATTACKER, 1, null, 93, 0),
            TacticalV2UnitSource(TacticalV2SourceKey(TacticalV2SourceKind.REINFORCEMENT, "r"),
                BattleSide.DEFENDER, 2, 2, 89, 0))
        repeat(40) { seed ->
            val random = Random(seed)
            var ledger = TacticalV2TroopLedger.fromSources(sources)
            repeat(500) {
                val source = sources[random.nextInt(sources.size)]
                val balance = ledger.balances.getValue(source.key)
                if (balance.active > 0) {
                    ledger = when (random.nextInt(4)) {
                        0 -> ledger.captureOne(source.key)
                        1 -> if (source.stationary) ledger else ledger.escapeOne(source.key)
                        2 -> ledger.takeCasualties(source.key, random.nextInt(balance.active + 1))
                        else -> if (source.key.kind == TacticalV2SourceKind.RETINUE) ledger.escape(source.key) else ledger
                    }
                }
                ledger.balances.forEach { (_, b) ->
                    assertEquals(b.initial.toLong(), b.active.toLong() + b.escaped + b.casualties + b.captured)
                }
            }
        }
    }

    @Test
    fun `captured defaults to zero and invalid balances cannot overflow into conservation`() {
        assertEquals(0, TacticalV2TroopBalance(3, 3, 0, 0).captured)
        assertFailsWith<IllegalArgumentException> { TacticalV2TroopBalance(3, 3, 0, 0, -1) }
        assertFailsWith<IllegalArgumentException> { TacticalV2TroopBalance(3, 3, 0, 0, 1) }
        assertFailsWith<IllegalArgumentException> {
            TacticalV2TroopBalance(1, Int.MAX_VALUE, Int.MAX_VALUE, 2, 1)
        }
    }

    @Test
    fun `ledger insertion preserves sorted sources and all previous balances and rejects duplicate identity`() {
        val first = retinue(2, 5)
        val next = retinue(1, 7)
        val initial = TacticalV2TroopLedger.fromSources(listOf(first)).captureOne(first.key)
        val after = initial.addSource(next)
        assertEquals(listOf(next, first), after.sources)
        assertEquals(initial.balances.getValue(first.key), after.balances.getValue(first.key))
        assertEquals(TacticalV2TroopBalance(7, 7, 0, 0), after.balances.getValue(next.key))
        assertFailsWith<IllegalArgumentException> { after.addSource(next) }
        assertFailsWith<IllegalArgumentException> { after.addSource(next.copy(initialTroops = 8)) }
    }

    @Test
    fun `legacy bulk escape remains retinue only while single escape supports new mobile sources`() {
        for (kind in listOf(TacticalV2SourceKind.PRODUCED, TacticalV2SourceKind.REINFORCEMENT)) {
            val source = TacticalV2UnitSource(TacticalV2SourceKey(kind, "event-a"), BattleSide.ATTACKER, 1, 1, 2, 0)
            val ledger = TacticalV2TroopLedger.fromSources(listOf(source))
            assertFailsWith<IllegalArgumentException> { ledger.escape(source.key) }
            assertEquals(TacticalV2TroopBalance(2, 1, 1, 0), ledger.escapeOne(source.key).balances.getValue(source.key))
        }
    }

    private fun retinue(id: Int, troops: Int) = TacticalV2UnitSource(
        TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, id.toString()), BattleSide.ATTACKER, id, id, troops, 0)
}
