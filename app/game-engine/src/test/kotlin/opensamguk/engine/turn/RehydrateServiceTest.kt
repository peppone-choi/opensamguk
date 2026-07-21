package opensamguk.engine.turn

import opensamguk.common.world.WorldId
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RehydrateServiceTest {

    @Test
    fun `game env bootstrap upsert binds canonical world and scoped partial conflict target`() {
        val jdbc = emptyJdbc()

        RehydrateService(jdbc, "hidden-seed", WorldId(7)).rehydrate()

        val sql = ArgumentCaptor.forClass(String::class.java)
        val parameters = ArgumentCaptor.forClass(SqlParameterSource::class.java)
        verify(jdbc).update(sql.capture(), parameters.capture())

        val normalizedSql = sql.value.replace(Regex("\\s+"), " ").trim()
        assertContains(normalizedSql, """INSERT INTO game_kv (world_id, "table", namespace, key, value)""")
        assertContains(normalizedSql, """VALUES (:worldId, 'game_env', 'game_env', 'obfuscatedNamePool', :value::jsonb)""")
        assertContains(
            normalizedSql,
            """ON CONFLICT (world_id, "table", namespace, key) WHERE "table" <> 'inheritance' AND world_id IS NOT NULL""",
        )
        assertEquals(7, parameters.value.getValue("worldId"))
    }

    @Test
    fun `inheritance kv rehydrate remains global`() {
        val jdbc = emptyJdbc()

        RehydrateService(jdbc, "hidden-seed", WorldId(7)).rehydrate()

        val sql = ArgumentCaptor.forClass(String::class.java)
        verify(jdbc, atLeastOnce()).queryForList(sql.capture(), any(SqlParameterSource::class.java))
        val inheritanceQuery = sql.allValues.single { it.contains("namespace LIKE 'inheritance_%'") }
        assertFalse(inheritanceQuery.contains(":worldId"))
    }

    private fun emptyJdbc(): NamedParameterJdbcTemplate {
        val jdbc = mock(NamedParameterJdbcTemplate::class.java)
        `when`(jdbc.queryForList(anyString(), any(SqlParameterSource::class.java))).thenReturn(emptyList())
        return jdbc
    }
}
