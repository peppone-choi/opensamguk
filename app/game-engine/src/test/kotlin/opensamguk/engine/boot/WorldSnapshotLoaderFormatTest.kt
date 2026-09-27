package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorldSnapshotLoaderFormatTest {
    private class SingleWorldJdbc(private val configJson: String) : JdbcTemplate() {
        override fun <T> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): MutableList<T> {
            check(sql.contains("FROM world_state")) { "unexpected query before world-format validation: $sql" }
            val rs = mock(ResultSet::class.java)
            `when`(rs.getString("meta")).thenReturn("{}")
            `when`(rs.getString("config")).thenReturn(configJson)
            `when`(rs.getString("status")).thenReturn("OPEN")
            `when`(rs.getInt("id")).thenReturn(1)
            `when`(rs.getInt("current_year")).thenReturn(200)
            `when`(rs.getInt("current_month")).thenReturn(1)
            `when`(rs.getInt("current_phase")).thenReturn(1)
            `when`(rs.getInt("tick_seconds")).thenReturn(60)
            return mutableListOf(checkNotNull(rowMapper.mapRow(rs, 0)))
        }
    }

    @Test fun `engine boot rejects unmarked old-key and sammo worlds before loading actors`() {
        val invalid = listOf(
            "{}" to "worldFormat is missing",
            "{\"ruleProfile\":\"HWIHA\"}" to "retired world key",
            "{\"worldFormat\":\"SAMMO\"}" to "unsupported worldFormat",
            "{\"worldFormat\":\"GENERAL_RETAINER_CAMPAIGN\",\"hwi" + "haCountyWarehouse\":{}}"
                to "retired world key",
        )
        for ((config, expected) in invalid) {
            val loader = WorldSnapshotLoader(SingleWorldJdbc(config),
                SeedBootstrap(seedEnabled = false, worldId = WorldId(1)), WorldId(1))
            val error = assertFailsWith<IllegalArgumentException> { loader.buildSnapshot() }
            assertTrue(error.message.orEmpty().contains(expected), error.message)
        }
    }
}
