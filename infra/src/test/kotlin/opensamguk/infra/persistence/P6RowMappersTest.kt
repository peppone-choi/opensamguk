package opensamguk.infra.persistence

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T0.1 unit coverage for the P6 row mappers (row map <-> infra row data class <-> column map).
 * No DB — exercises the pure mapping seam the flush channels + rehydrate consume.
 */
class P6RowMappersTest {

    @Test
    fun `MessageRow round-trips and toColumns omits the SERIAL id`() {
        val t = Instant.parse("2026-05-31T00:00:00Z")
        val row = linkedMapOf<String, Any?>(
            "id" to 5, "mailbox" to 9001, "type" to "national", "src" to 10, "dest" to 20,
            "time" to t, "valid_until" to MessageRowMapper.VALID_UNTIL_SENTINEL,
            "message" to """{"action":"scout","text":"등용"}""",
        )
        val m = MessageRowMapper.fromRow(row)
        assertEquals(9001, m.mailbox)
        assertEquals("national", m.type)
        assertEquals(MessageRowMapper.VALID_UNTIL_SENTINEL, m.validUntil)
        val cols = MessageRowMapper.toColumns(m)
        assertTrue("id" !in cols, "INSERT column map must omit the SERIAL id")
        assertEquals("""{"action":"scout","text":"등용"}""", cols["message"])
        assertEquals(9001, cols["mailbox"])
    }

    @Test
    fun `GameKvRow carries a null value (delete-on-null) and a present value`() {
        val present = GameKvRowMapper.fromRow(
            linkedMapOf("table" to "game_env", "namespace" to "global", "key" to "lastNpcTroopLeaderID", "value" to "5"),
        )
        assertEquals("game_env", present.table)
        assertEquals("5", present.valueJson)

        val deleted = GameKvRowMapper.fromRow(
            linkedMapOf("table" to "game_env", "namespace" to "global", "key" to "obfuscatedNamePool", "value" to null),
        )
        assertNull(deleted.valueJson, "null value signals delete-on-null")
        assertNull(GameKvRowMapper.toColumns(deleted)["value"])
    }
}
