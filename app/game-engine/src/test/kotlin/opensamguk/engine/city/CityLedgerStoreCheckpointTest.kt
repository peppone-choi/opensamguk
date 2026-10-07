package opensamguk.engine.city

import opensamguk.common.world.WorldId
import opensamguk.engine.turn.ChangeRecorder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import java.sql.ResultSet
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals

class CityLedgerStoreCheckpointTest {
    @Test
    fun `loaded checkpoint restores original world and drops failed new cities without a reload`() {
        val jdbc = LedgerReads()
        val ledger = CityLedgerStore(jdbc)
        val world = WorldId(1)
        assertEquals(CityLedgerEntry(100, 200, 3), ledger.entry(world, 1))
        val checkpoint = ledger.checkpoint()
        ledger.adjust(world, ChangeRecorder(), 1, goldDelta = 7)
        ledger.adjust(world, ChangeRecorder(), 99, goldDelta = 8)
        ledger.entries(WorldId(2))

        ledger.restore(checkpoint)

        assertEquals(mapOf(1 to CityLedgerEntry(100, 200, 3)), ledger.entries(world))
        assertEquals(CityLedgerEntry.EMPTY, ledger.entry(world, 99))
        assertEquals(2, jdbc.reads, "restored loaded world must not reload")
    }

    @Test
    fun `cold checkpoint stays lazy and restores the unloaded marker after failure`() {
        val jdbc = LedgerReads()
        val ledger = CityLedgerStore(jdbc)
        val world = WorldId(1)
        val checkpoint = ledger.checkpoint()
        assertEquals(0, jdbc.reads)
        ledger.adjust(world, ChangeRecorder(), 1, goldDelta = 7)
        ledger.adjust(world, ChangeRecorder(), 99, goldDelta = 8)

        ledger.restore(checkpoint)

        assertEquals(1, jdbc.reads, "restore itself must not read")
        assertEquals(mapOf(1 to CityLedgerEntry(100, 200, 3)), ledger.entries(world))
        assertEquals(2, jdbc.reads, "a restored cold cache must read again")
    }

    private class LedgerReads : NamedParameterJdbcTemplate(mock(DataSource::class.java)) {
        var reads = 0
        override fun query(sql: String, paramSource: SqlParameterSource, rch: RowCallbackHandler) {
            reads++
            val rs = mock(ResultSet::class.java)
            `when`(rs.getInt("city_id")).thenReturn(1)
            `when`(rs.getLong("gold")).thenReturn(100L * (paramSource.getValue("world_id") as Int))
            `when`(rs.getLong("rice")).thenReturn(200L)
            `when`(rs.getInt("garrison")).thenReturn(3)
            rch.processRow(rs)
        }
    }
}
