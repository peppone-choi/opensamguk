package opensamguk.logic.battle.realtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TacticalV2SourceRegistryTest {
    @Test
    fun `source enum ordinals and existing key sorting stay unchanged`() {
        assertEquals(0, TacticalV2SourceKind.RETINUE.ordinal)
        assertEquals(1, TacticalV2SourceKind.CITY_GARRISON_BUGOK.ordinal)
        assertEquals(2, TacticalV2SourceKind.PRODUCED.ordinal)
        assertEquals(3, TacticalV2SourceKind.REINFORCEMENT.ordinal)
        val oldKeys = listOf(TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "2"),
            TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "10"),
            TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "b", 2),
            TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "a", 1))
        assertEquals(listOf(oldKeys[1], oldKeys[0], oldKeys[3], oldKeys[2]), oldKeys.sorted())
        assertEquals(oldKeys.sorted(), (oldKeys + produced().key + reinforcement().key).sorted().take(4))
    }

    @Test
    fun `new source keys are bounded whitespace free event identifiers without city identity`() {
        for (kind in listOf(TacticalV2SourceKind.PRODUCED, TacticalV2SourceKind.REINFORCEMENT)) {
            assertEquals("a".repeat(128), TacticalV2SourceKey(kind, "a".repeat(128)).sourceId)
            for (id in listOf("", " ", "a b", "a\tb", "a\nb", "a".repeat(129))) {
                assertFailsWith<IllegalArgumentException> { TacticalV2SourceKey(kind, id) }
            }
            assertFailsWith<IllegalArgumentException> { TacticalV2SourceKey(kind, "a", 1) }
        }
    }

    @Test
    fun `produced sources require owners and reinforcement requires owners and commanders`() {
        assertEquals(null, produced().commanderGeneralId)
        assertFailsWith<IllegalArgumentException> { produced().copy(ownerGeneralId = null) }
        assertFailsWith<IllegalArgumentException> { produced().copy(ownerGeneralId = 0) }
        assertFailsWith<IllegalArgumentException> { produced().copy(commanderGeneralId = 0) }
        assertFailsWith<IllegalArgumentException> { reinforcement().copy(ownerGeneralId = null) }
        assertFailsWith<IllegalArgumentException> { reinforcement().copy(commanderGeneralId = null) }
        assertFailsWith<IllegalArgumentException> { reinforcement().copy(commanderGeneralId = -1) }
    }

    @Test
    fun `seed IDs follow canonical source order and retain losses and large integer ranges`() {
        val a = retinue(2, 3)
        val b = retinue(10, Int.MAX_VALUE)
        val ledger = TacticalV2TroopLedger.fromSources(listOf(a, b)).takeCasualties(b.key, Int.MAX_VALUE)
        val registry = TacticalV2SourceRegistry.fromLedger(ledger)
        assertEquals(1L..Int.MAX_VALUE.toLong(), registry.soldierIds[b.key])
        assertEquals(2147483648L..2147483650L, registry.soldierIds[a.key])
        val after = registry.addSource(produced(), "p", debit())
        assertEquals(2147483651L..2147483652L, after.soldierIds[produced().key])
        assertEquals(2147483652L, after.maximumSoldierId)
    }

    @Test
    fun `dynamic IDs append despite new key sort order and never reuse escaped captured or dead IDs`() {
        val initial = retinue(1, 3)
        var registry = TacticalV2SourceRegistry.fromLedger(TacticalV2TroopLedger.fromSources(listOf(initial)))
        registry = registry.withLedger(registry.ledger.escapeOne(initial.key).captureOne(initial.key)
            .takeCasualties(initial.key, 1))
        val joined = registry.addSource(reinforcement(), "r", arrivalEventId = "arrival-a")
        val after = joined.addSource(produced(), "p", debit())
        assertEquals(1L..3L, after.soldierIds[initial.key])
        assertEquals(4L..6L, after.soldierIds[reinforcement().key])
        assertEquals(7L..8L, after.soldierIds[produced().key])
        assertEquals(listOf(initial.key, produced().key, reinforcement().key), after.ledger.sources.map { it.key })
        assertEquals(TacticalV2TroopBalance(3, 0, 1, 1, 1), after.ledger.balances[initial.key])
    }

    @Test
    fun `idempotent replay returns same receipt without reverting intervening losses or later additions`() {
        val joined = emptyRegistry().addSource(produced(), "p", debit())
        val after = joined.withLedger(joined.ledger.captureOne(produced().key))
            .addSource(reinforcement(), "r", arrivalEventId = "arrival-a")
        val replay = after.addSource(produced(), "p", debit())
        assertSame(after, replay)
        assertSame(joined.registrations.getValue("p"), replay.registrations.getValue("p"))
        assertEquals(1, replay.ledger.balances.getValue(produced().key).captured)
        assertEquals(5L, replay.maximumSoldierId)
        assertEquals(debit(), replay.registrations.getValue("p").productionDebit)
        assertEquals("arrival-a", replay.registrations.getValue("r").arrivalEventId)
    }

    @Test
    fun `idempotency content changes and duplicate source under different key are rejected atomically`() {
        val after = emptyRegistry().addSource(produced(), "p", debit())
        val differentSources = listOf(produced().copy(initialTroops = 3),
            produced().copy(side = BattleSide.DEFENDER), produced().copy(sourceRevision = 1),
            produced("production-b"), produced().copy(commanderGeneralId = 1),
            produced().copy(ownerGeneralId = 2))
        differentSources.forEach { source ->
            assertFailsWith<IllegalArgumentException> { after.addSource(source, "p", debit()) }
        }
        assertFailsWith<IllegalArgumentException> { after.addSource(produced(), "other", debit()) }
        assertFailsWith<IllegalArgumentException> {
            after.addSource(produced(), "p", debit().copy(resourceLedgerId = "different"))
        }
        assertFailsWith<IllegalArgumentException> {
            after.addSource(produced(), "p", debit().copy(populationSource = TacticalV2PopulationSource.RESERVE))
        }
        assertFailsWith<IllegalArgumentException> { after.addSource(produced(), "p", debit(), "arrival-a") }
        val joined = after.addSource(reinforcement(), "r", arrivalEventId = "arrival-a")
        assertFailsWith<IllegalArgumentException> {
            joined.addSource(reinforcement(), "r", arrivalEventId = "different")
        }
        assertEquals(2L, after.maximumSoldierId)
        assertEquals(TacticalV2TroopBalance(2, 2, 0, 0), after.ledger.balances[produced().key])
    }

    @Test
    fun `registration requires the corresponding provenance and matching production owner facts`() {
        val registry = emptyRegistry()
        assertFailsWith<IllegalArgumentException> { registry.addSource(produced(), "p") }
        assertFailsWith<IllegalArgumentException> { registry.addSource(produced(), " ", debit()) }
        assertFailsWith<IllegalArgumentException> {
            registry.addSource(produced(), "p", debit().copy(buildingOwnerGeneralId = 2))
        }
        assertFailsWith<IllegalArgumentException> {
            registry.addSource(produced(), "p", debit().copy(buildingOwnerSide = BattleSide.DEFENDER))
        }
        assertFailsWith<IllegalArgumentException> { registry.addSource(reinforcement(), "r") }
        assertFailsWith<IllegalArgumentException> {
            registry.addSource(reinforcement(), "r", arrivalEventId = "wrong-event")
        }
        assertFailsWith<IllegalArgumentException> {
            registry.addSource(reinforcement(), "r", debit(), "arrival-a")
        }
        assertFailsWith<IllegalArgumentException> { registry.addSource(retinue(1, 1), "legacy") }
        assertFailsWith<IllegalArgumentException> { debit().copy(resourceLedgerId = " ") }
        assertFailsWith<IllegalArgumentException> { debit().copy(buildingOwnerGeneralId = 0) }
        assertEquals(0L, registry.maximumSoldierId)
        assertEquals(emptyMap(), registry.ledger.balances)
    }

    @Test
    fun `registry snapshots expose immutable maps and cannot adopt unknown sources`() {
        val after = emptyRegistry().addSource(produced(), "p", debit())
        assertFailsWith<UnsupportedOperationException> {
            (after.soldierIds as MutableMap<TacticalV2SourceKey, LongRange>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (after.registrations as MutableMap<String, TacticalV2SourceRegistration>).clear()
        }
        assertFailsWith<IllegalArgumentException> {
            after.withLedger(after.ledger.addSource(reinforcement()))
        }
        assertFailsWith<IllegalArgumentException> {
            after.withLedger(TacticalV2TroopLedger.fromSources(listOf(produced().copy(initialTroops = 4))))
        }
    }

    @Test
    fun `both new source kinds round trip through deployment codec without changing its version`() {
        val units = listOf(produced(), reinforcement())
        val cells = units.mapIndexed { index, source -> source.key to TacticalV2Cell(1, index) }.toMap()
        val allowed = mapOf(BattleSide.ATTACKER to cells.values.toSet(), BattleSide.DEFENDER to emptySet())
        val deployment = TacticalV2Deployment(1, "a".repeat(64), units, cells, allowed)
        val bytes = TacticalV2DeploymentCodec.encode(deployment)
        val restored = TacticalV2DeploymentCodec.decode(bytes, 1, "a".repeat(64), allowed)
        assertEquals(deployment, restored)
        assertContentEquals(bytes, TacticalV2DeploymentCodec.encode(restored))
    }

    private fun emptyRegistry() = TacticalV2SourceRegistry.fromLedger(TacticalV2TroopLedger.fromSources(emptyList()))
    private fun produced(id: String = "production-a") = TacticalV2UnitSource(
        TacticalV2SourceKey(TacticalV2SourceKind.PRODUCED, id), BattleSide.ATTACKER, 1, null, 2, 0)
    private fun reinforcement() = TacticalV2UnitSource(
        TacticalV2SourceKey(TacticalV2SourceKind.REINFORCEMENT, "arrival-a"), BattleSide.ATTACKER, 2, 3, 3, 0)
    private fun retinue(id: Int, troops: Int) = TacticalV2UnitSource(
        TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, id.toString()), BattleSide.ATTACKER, id, id, troops, 0)
    private fun debit() = TacticalV2ProductionDebit("debit-a", TacticalV2PopulationSource.HOME_CITY, 1, BattleSide.ATTACKER)
}
