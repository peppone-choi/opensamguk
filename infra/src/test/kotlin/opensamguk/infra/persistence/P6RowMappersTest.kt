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
    fun `NgBettingRow round-trips with a null user_id`() {
        val row = linkedMapOf<String, Any?>(
            "id" to 1, "betting_id" to 3, "general_id" to 0, "user_id" to null,
            "betting_type" to "[-1]", "amount" to 500,
        )
        val b = NgBettingRowMapper.fromRow(row)
        assertNull(b.userId)
        assertEquals("[-1]", b.bettingType)
        assertEquals(500, b.amount)
        val cols = NgBettingRowMapper.toColumns(b)
        assertNull(cols["user_id"])
        assertTrue("id" !in cols)
    }

    @Test
    fun `AuctionRow round-trips finished boolean and resource enum`() {
        val open = Instant.parse("2026-05-31T00:00:00Z")
        val close = Instant.parse("2026-05-31T01:00:00Z")
        val row = linkedMapOf<String, Any?>(
            "id" to 7, "type" to "uniqueItem", "finished" to false, "target" to "che_보검",
            "host_general_id" to 0, "req_resource" to "inheritPoint",
            "open_date" to open, "close_date" to close, "detail" to """{"amount":5000}""",
        )
        val a = AuctionRowMapper.fromRow(row)
        assertEquals("uniqueItem", a.type)
        assertEquals(false, a.finished)
        assertEquals("inheritPoint", a.reqResource)
        val cols = AuctionRowMapper.toColumns(a)
        assertTrue("id" !in cols)
        assertEquals("inheritPoint", cols["req_resource"])
        assertEquals(close, cols["close_date"])
    }

    @Test
    fun `AuctionBidRow round-trips a nullable owner`() {
        val date = Instant.parse("2026-05-31T00:30:00Z")
        val row = linkedMapOf<String, Any?>(
            "no" to 2, "auction_id" to 7, "owner" to null, "general_id" to 42,
            "amount" to 6060, "date" to date, "aux" to """{"raise":"1.01"}""",
        )
        val b = AuctionBidRowMapper.fromRow(row)
        assertNull(b.owner)
        assertEquals(6060, b.amount)
        val cols = AuctionBidRowMapper.toColumns(b)
        assertTrue("no" !in cols, "INSERT column map must omit the SERIAL no")
    }

    @Test
    fun `GameKvRow carries a null value (delete-on-null) and a present value`() {
        val present = GameKvRowMapper.fromRow(
            linkedMapOf("table" to "game_env", "namespace" to "global", "key" to "last_betting_id", "value" to "5"),
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
