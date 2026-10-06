package opensamguk.logic.battle.realtime

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class TacticalV2DeploymentTest {
    private val terrainSha = "a".repeat(64)

    @Test
    fun `all thirteen source units deploy at once and round trip without formation slots`() {
        val deployment = corpsDeployment(13)
        assertEquals(13, deployment.units.size)
        assertEquals(13, deployment.cells.size)
        val bytes = TacticalV2DeploymentCodec.encode(deployment)
        val restored = TacticalV2DeploymentCodec.decode(bytes, 1, terrainSha, deployment.allowedCells)
        assertEquals(deployment, restored)
        assertEquals(deployment.stateSha256(), restored.stateSha256())
        assertContentEquals(bytes, TacticalV2DeploymentCodec.encode(restored))
    }

    @Test
    fun `rehashed reversed spawn cell bytes are not canonical`() {
        val deployment = corpsDeployment(2)
        val bytes = TacticalV2DeploymentCodec.encode(deployment)
        val suffix = ByteBuffer.allocate(4 + 3 * 8 + 4).apply {
            putInt(3)
            (1..3).forEach { col -> putInt(1); putInt(col) }
            putInt(0)
        }.array()
        val suffixStart = bytes.size - suffix.size
        assertContentEquals(suffix, bytes.copyOfRange(suffixStart, bytes.size))
        val reversed = bytes.copyOf()
        val firstCell = suffixStart + 4
        val secondCell = firstCell + 8
        for (offset in 0 until 8) {
            val saved = reversed[firstCell + offset]
            reversed[firstCell + offset] = reversed[secondCell + offset]
            reversed[secondCell + offset] = saved
        }
        val repinnedSha = MessageDigest.getInstance("SHA-256").digest(reversed)
            .joinToString("") { "%02x".format(it) }
        assertEquals(64, repinnedSha.length)
        assertNotEquals(deployment.stateSha256(), repinnedSha)
        val failure = assertFailsWith<IllegalArgumentException> {
            TacticalV2DeploymentCodec.decode(reversed, 1, terrainSha, deployment.allowedCells)
        }
        assertEquals("noncanonical spawn cell order", failure.message)
    }

    @Test
    fun `only controlled corps sources may move or swap during joining`() {
        val before = corpsDeployment(2)
        val first = sourceKey(1)
        val second = sourceKey(2)
        val moved = before.move(first, TacticalV2Cell(1, 3), setOf(first), 0)
        assertEquals(TacticalV2Cell(1, 3), moved.cells.getValue(first))
        assertEquals(1, moved.revision)
        assertNotEquals(before.stateSha256(), moved.stateSha256())
        assertFailsWith<IllegalArgumentException> {
            before.move(second, TacticalV2Cell(1, 3), setOf(first), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            before.move(first, before.cells.getValue(second), setOf(first), 0)
        }
        val swapped = before.move(first, before.cells.getValue(second), setOf(first, second), 0)
        assertEquals(before.cells.getValue(second), swapped.cells.getValue(first))
        assertEquals(before.cells.getValue(first), swapped.cells.getValue(second))
        assertFailsWith<IllegalArgumentException> {
            moved.move(first, TacticalV2Cell(1, 4), setOf(first), 0)
        }
    }

    @Test
    fun `real city garrison source requires city identity and cannot be human moved`() {
        assertFailsWith<IllegalArgumentException> {
            TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "real-1")
        }
        val garrison = TacticalV2UnitSource(
            TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "real-1", 9),
            BattleSide.DEFENDER, null, null, 51, 4)
        val allowed = mapOf(BattleSide.ATTACKER to emptySet(),
            BattleSide.DEFENDER to setOf(TacticalV2Cell(4, 60), TacticalV2Cell(5, 60)))
        val deployment = TacticalV2Deployment(1, terrainSha, listOf(garrison),
            mapOf(garrison.key to TacticalV2Cell(4, 60)), allowed)
        assertFailsWith<IllegalArgumentException> {
            deployment.move(garrison.key, TacticalV2Cell(5, 60), setOf(garrison.key), 0)
        }
    }

    @Test
    fun `missing or duplicate source and wrong external spawn pin fail closed`() {
        val original = corpsDeployment(7)
        assertFailsWith<IllegalArgumentException> {
            original.copy(cells = original.cells - sourceKey(7))
        }
        assertFailsWith<IllegalArgumentException> {
            original.copy(units = original.units + original.units.first())
        }
        assertFailsWith<IllegalArgumentException> {
            TacticalV2DeploymentCodec.decode(TacticalV2DeploymentCodec.encode(original),
                1, terrainSha, mapOf(BattleSide.ATTACKER to emptySet(),
                    BattleSide.DEFENDER to emptySet()))
        }
    }

    @Test
    fun `source losses and city totals stay integer exact without four-token conversion`() {
        val field = TacticalV2UnitSource(sourceKey(1), BattleSide.ATTACKER, 7, 7, 100, 3)
        val cityOne = TacticalV2UnitSource(
            TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "real-1", 9),
            BattleSide.DEFENDER, null, null, 53, 4)
        val cityTwo = TacticalV2UnitSource(
            TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "real-2", 9),
            BattleSide.DEFENDER, null, null, 47, 5)
        val ledger = TacticalV2TroopLedger.fromSources(listOf(cityTwo, field, cityOne))
            .takeCasualties(cityOne.key, 13)
            .takeCasualties(field.key, 25)
            .escape(field.key)
        assertEquals(87, ledger.cityRemaining(9))
        assertEquals(TacticalV2TroopBalance(100, 0, 75, 25), ledger.balances.getValue(field.key))
        assertFailsWith<IllegalArgumentException> { ledger.escape(cityTwo.key) }
        assertFailsWith<IllegalArgumentException> {
            TacticalV2TroopBalance(53, 40, 0, 0)
        }
    }

    @Test
    fun `deployment owns immutable copies of constructor copy and decoded collections`() {
        val original = corpsDeployment(2)
        val inputUnits = original.units.toMutableList()
        val inputCells = original.cells.toMutableMap()
        val attackerCells = original.allowedCells.getValue(BattleSide.ATTACKER).toMutableSet()
        val inputAllowed = mutableMapOf(BattleSide.ATTACKER to attackerCells,
            BattleSide.DEFENDER to mutableSetOf<TacticalV2Cell>())
        val frozen = TacticalV2Deployment(1, terrainSha, inputUnits, inputCells, inputAllowed)
        val beforeSha = frozen.stateSha256()
        val copied = frozen.copy(cells = inputCells, allowedCells = inputAllowed)
        val decoded = TacticalV2DeploymentCodec.decode(TacticalV2DeploymentCodec.encode(frozen),
            1, terrainSha, inputAllowed)

        inputUnits.clear()
        inputCells.clear()
        attackerCells.clear()
        inputAllowed.clear()
        for (value in listOf(frozen, copied, decoded)) {
            assertEquals(2, value.units.size)
            assertEquals(2, value.cells.size)
            assertEquals(3, value.allowedCells.getValue(BattleSide.ATTACKER).size)
            assertEquals(beforeSha, value.stateSha256())
            assertEquals(0, value.revision)
            assertFailsWith<UnsupportedOperationException> {
                (value.units as MutableList<TacticalV2UnitSource>).clear()
            }
            assertFailsWith<UnsupportedOperationException> {
                (value.cells as MutableMap<TacticalV2SourceKey, TacticalV2Cell>).clear()
            }
            assertFailsWith<UnsupportedOperationException> {
                (value.allowedCells as MutableMap<BattleSide, Set<TacticalV2Cell>>).clear()
            }
            assertFailsWith<UnsupportedOperationException> {
                (value.allowedCells.getValue(BattleSide.ATTACKER) as MutableSet<TacticalV2Cell>).clear()
            }
        }
    }

    @Test
    fun `troop ledger owns immutable constructor and copy balances`() {
        val source = TacticalV2UnitSource(sourceKey(1), BattleSide.ATTACKER, 7, 7, 100, 3)
        val inputSources = mutableListOf(source)
        val inputBalances = mutableMapOf(source.key to TacticalV2TroopBalance(100, 100, 0, 0))
        val ledger = TacticalV2TroopLedger(inputSources, inputBalances)
        val copied = ledger.copy(sources = inputSources, balances = inputBalances)
        inputSources.clear()
        inputBalances.clear()
        for (value in listOf(ledger, copied)) {
            assertEquals(listOf(source), value.sources)
            assertEquals(TacticalV2TroopBalance(100, 100, 0, 0), value.balances.getValue(source.key))
            assertFailsWith<UnsupportedOperationException> {
                (value.sources as MutableList<TacticalV2UnitSource>).clear()
            }
            assertFailsWith<UnsupportedOperationException> {
                (value.balances as MutableMap<TacticalV2SourceKey, TacticalV2TroopBalance>).clear()
            }
        }
    }

    private fun corpsDeployment(size: Int): TacticalV2Deployment {
        val units = (1..size).map { id ->
            TacticalV2UnitSource(sourceKey(id), BattleSide.ATTACKER, 7, 7, 100 + id, 3)
        }.sortedBy { it.key }
        val allowed = mapOf(BattleSide.ATTACKER to (1..(size + 1)).map { TacticalV2Cell(1, it) }.toSet(),
            BattleSide.DEFENDER to emptySet())
        return TacticalV2Deployment(1, terrainSha, units,
            units.associate { it.key to TacticalV2Cell(1, it.key.sourceId.toInt()) }, allowed)
    }

    private fun sourceKey(id: Int) = TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, id.toString())
}
