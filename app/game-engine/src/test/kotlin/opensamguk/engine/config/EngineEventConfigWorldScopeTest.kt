package opensamguk.engine.config

import opensamguk.common.world.WorldId
import opensamguk.engine.boot.SeedBootstrap
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineEventConfigWorldScopeTest {

    @Test
    fun `event cold-load contains only the configured world's rows`() {
        val jdbc = WorldScopedJdbcTemplate(
            mapOf(
                1 to listOf(EventFixture(id = 101, target = "pre_month")),
                2 to listOf(EventFixture(id = 202, target = "month")),
            ),
        )
        val bootstrap = SeedBootstrap(seedEnabled = false, worldId = WorldId(2))

        val store = EngineEventConfig().createEventStore(jdbc, bootstrap, WorldId(2))

        assertEquals(listOf(202), store.allRows().map { it.id })
        val eventQuery = jdbc.eventQueries.single()
        assertTrue(eventQuery.sql.contains("WHERE world_id = ?"))
        assertEquals(listOf(2), eventQuery.arguments)
    }

    private data class EventFixture(
        val id: Int,
        val target: String,
        val priority: Int = 100,
        val condition: String = "true",
        val action: String = "[]",
    )

    private data class EventQuery(val sql: String, val arguments: List<Any?>)

    private class WorldScopedJdbcTemplate(
        private val eventsByWorld: Map<Int, List<EventFixture>>,
    ) : JdbcTemplate() {
        val eventQueries = mutableListOf<EventQuery>()

        override fun <T> query(sql: String, rowMapper: RowMapper<T>): MutableList<T> =
            queryRows(sql, rowMapper, emptyList())

        override fun <T> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): MutableList<T> =
            queryRows(sql, rowMapper, args.toList())

        private fun <T> queryRows(sql: String, rowMapper: RowMapper<T>, arguments: List<Any?>): MutableList<T> = when {
            sql.contains("FROM world_state") -> mutableListOf(checkNotNull(rowMapper.mapRow(worldStateResultSet(), 0)))
            sql.contains("FROM event") -> {
                eventQueries += EventQuery(sql, arguments)
                val rows = if (arguments.isEmpty()) {
                    eventsByWorld.values.flatten()
                } else {
                    eventsByWorld.getValue(arguments.single() as Int)
                }
                rows.mapIndexed { index, event -> checkNotNull(rowMapper.mapRow(eventResultSet(event), index)) }.toMutableList()
            }
            else -> error("Unexpected query: $sql")
        }

        private fun worldStateResultSet(): ResultSet = mock(ResultSet::class.java).also { resultSet ->
            `when`(resultSet.getBoolean(1)).thenReturn(false)
        }

        private fun eventResultSet(event: EventFixture): ResultSet = mock(ResultSet::class.java).also { resultSet ->
            `when`(resultSet.getInt("id")).thenReturn(event.id)
            `when`(resultSet.getString("target_code")).thenReturn(event.target)
            `when`(resultSet.getInt("priority")).thenReturn(event.priority)
            `when`(resultSet.getString("condition_json")).thenReturn(event.condition)
            `when`(resultSet.getString("action_json")).thenReturn(event.action)
        }
    }
}
