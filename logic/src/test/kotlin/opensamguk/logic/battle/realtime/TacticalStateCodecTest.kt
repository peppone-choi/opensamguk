package opensamguk.logic.battle.realtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TacticalStateCodecTest {
    private val field = Battlefield(192, "FIELD", List(64) { "P".repeat(64) })
    private fun retinue(id: Int) = Retinue(id, GeneralStats(id, 80, 70, 60, 50, 40), 123,
        UnitKind.CAVALRY, 50, 90, 10, 80, true)

    @Test
    fun `checkpoint restores an active tactical state with its exact authority hash`() {
        val attacker = BattleDeployment.default(BattleSide.ATTACKER, 1, listOf(retinue(1)))
        val defender = BattleDeployment.default(BattleSide.DEFENDER, 2, listOf(retinue(2)))
        val initial = TacticalBattle.start(17, field, attacker, defender, setOf(BattleSide.ATTACKER))
        val state = TacticalBattle.step(initial,
            listOf(TacticalCommand(0, 1, BattleSide.ATTACKER, null, BattleOrder.CHARGE))).state
        val body = TacticalStateCodec.encode(state)
        val restored = TacticalStateCodec.decode(body, TacticalBattle.stateHash(state))
        assertEquals(state, restored)
        assertEquals(TacticalBattle.stateHash(state), TacticalBattle.stateHash(restored))
        assertEquals(body.toList(), TacticalStateCodec.encode(restored).toList())
    }

    @Test
    fun `wrong hash and damaged compressed body cannot restore`() {
        val state = TacticalState(1, field, 0, emptyList(), emptySet())
        val body = TacticalStateCodec.encode(state)
        assertFailsWith<IllegalArgumentException> { TacticalStateCodec.decode(body, "0".repeat(64)) }
        assertFailsWith<IllegalArgumentException> {
            TacticalStateCodec.decode(byteArrayOf(0, 1, 2), TacticalBattle.stateHash(state))
        }
        assertFailsWith<IllegalArgumentException> {
            TacticalStateCodec.decode(body + byteArrayOf(0), TacticalBattle.stateHash(state))
        }
    }

    @Test
    fun `version trailing plain bytes and inflated size limit reject corrupt checkpoints`() {
        val state = TacticalState(1, field, 0, emptyList(), emptySet())
        val expectedHash = TacticalBattle.stateHash(state)
        val plain = InflaterInputStream(ByteArrayInputStream(TacticalStateCodec.encode(state)))
            .use { it.readBytes() }
        fun deflate(bytes: ByteArray): ByteArray = ByteArrayOutputStream().let { output ->
            DeflaterOutputStream(output).use { it.write(bytes) }
            output.toByteArray()
        }
        val wrongVersion = plain.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertEquals("battle checkpoint version mismatch", assertFailsWith<IllegalArgumentException> {
            TacticalStateCodec.decode(deflate(wrongVersion), expectedHash)
        }.message)
        assertEquals("battle checkpoint has trailing bytes", assertFailsWith<IllegalArgumentException> {
            TacticalStateCodec.decode(deflate(plain + byteArrayOf(0)), expectedHash)
        }.message)
        assertEquals("battle checkpoint exceeds codec limit", assertFailsWith<IllegalArgumentException> {
            TacticalStateCodec.decode(deflate(ByteArray(70_000)), expectedHash)
        }.message)
        assertEquals("battle checkpoint corrupt", assertFailsWith<IllegalArgumentException> {
            TacticalStateCodec.decode(deflate(plain.copyOf(4)), expectedHash)
        }.message)
    }

    @Test
    fun `wall and gate checkpoint preserves map and unit state`() {
        val rows = List(64) { row -> if (row == 10) "P".repeat(11) + "W" + "P".repeat(52) else "P".repeat(64) }
        val siegeField = Battlefield(31, "FORTRESS", rows)
        val unit = TacticalUnit(BattleSide.ATTACKER, FormationSlot.CENTER, retinue(1),
            10, 10, order = BattleOrder.ATTACK)
        val state = TacticalState(72, siegeField, 87, listOf(unit), setOf(BattleSide.ATTACKER),
            gateRow = 10, gateCol = 11, gateHp = 4)
        assertEquals(state, TacticalStateCodec.decode(TacticalStateCodec.encode(state),
            TacticalBattle.stateHash(state)))
    }
}
