package opensamguk.infra.persistence

import opensamguk.common.world.WorldId
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import java.sql.ResultSet
import javax.sql.DataSource
import kotlin.test.*

class ReservedTurnCopyGuardTest {
    private class SourceJdbc(var actions: List<String>) : NamedParameterJdbcTemplate(mock(DataSource::class.java)) {
        var reads = 0
        val copied = mutableListOf<String>()
        override fun <T : Any?> query(sql: String, params: SqlParameterSource, mapper: RowMapper<T>): List<T> {
            reads++
            return actions.mapIndexed { index, action ->
                val rs = mock(ResultSet::class.java)
                `when`(rs.getInt("turn_idx")).thenReturn(index)
                `when`(rs.getString("action_code")).thenReturn(action)
                `when`(rs.getString("arg")).thenReturn("{}")
                `when`(rs.getString("brief")).thenReturn(action)
                mapper.mapRow(rs, index)!!
            }
        }
        override fun update(sql: String, params: SqlParameterSource): Int {
            copied.add(params.getValue("action_code") as String)
            return 1
        }
    }
    @Test fun `mixed selected rows reject before even a legacy prefix is copied`() {
        val jdbc = SourceJdbc(listOf("che_농지개간", "action.enlist"))
        assertFailsWith<ReservedTurnRepository.UnsupportedInputCopy> {
            ReservedTurnRepository(jdbc).repeatGeneralTurn(WorldId(1), 10, 2)
        }
        assertEquals(1, jdbc.reads)
        assertTrue(jdbc.copied.isEmpty())
    }
    @Test fun `legacy selected snapshot copies normally and no op boundaries do not read`() {
        val jdbc = SourceJdbc(listOf("che_농지개간", "che_상업투자"))
        val repo = ReservedTurnRepository(jdbc)
        repo.repeatGeneralTurn(WorldId(1), 10, 2)
        assertEquals(listOf("che_농지개간", "che_상업투자"), jdbc.copied)
        for (amount in listOf(0, ReservedTurnRepository.MAX_GENERAL_TURNS, -1)) repo.repeatGeneralTurn(WorldId(1), 10, amount)
        assertEquals(1, jdbc.reads)
    }
}
