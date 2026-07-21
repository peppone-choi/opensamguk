package opensamguk.infra.read

import opensamguk.common.world.WorldId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContactReaderIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var reader: ContactReader

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()
        jdbc = NamedParameterJdbcTemplate(
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
        )
        reader = ContactReader(jdbc)
        insertWorld(701)
        insertWorld(702)
        insertMessage(worldId = 701, mailbox = 31, text = "첫 번째 월드")
        insertMessage(worldId = 702, mailbox = 42, text = "두 번째 월드")
    }

    @AfterAll
    fun tearDown() {
        if (::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `findMessage isolates equal local ids by world`() {
        val first = reader.findMessage(WorldId(701), MESSAGE_ID)
        val second = reader.findMessage(WorldId(702), MESSAGE_ID)

        assertEquals(MESSAGE_ID, first?.id)
        assertEquals(31, first?.mailbox)
        assertEquals("첫 번째 월드", first?.text)
        assertEquals(MESSAGE_ID, second?.id)
        assertEquals(42, second?.mailbox)
        assertEquals("두 번째 월드", second?.text)
        assertNull(reader.findMessage(WorldId(703), MESSAGE_ID))
        assertNull(reader.findMessage(WorldId(701), MESSAGE_ID + 1))
    }

    private fun insertWorld(worldId: Int) {
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (:world_id, :scenario_code, 200, 1, 3600)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId)
                .addValue("scenario_code", "fixture-$worldId"),
        )
    }

    private fun insertMessage(worldId: Int, mailbox: Int, text: String) {
        jdbc.update(
            """
            INSERT INTO message (world_id, id, mailbox, type, src, dest, time, valid_until, message)
            VALUES (
                :world_id,
                :id,
                :mailbox,
                'private',
                10,
                20,
                TIMESTAMPTZ '2026-07-21 00:00:00+00',
                TIMESTAMPTZ '9999-12-31 23:59:59+00',
                jsonb_build_object(
                    'src', jsonb_build_object('id', 10, 'nation_id', 1),
                    'dest', jsonb_build_object('id', 20, 'nation_id', 2),
                    'text', :text,
                    'option', jsonb_build_object('action', 'scout')
                )
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId)
                .addValue("id", MESSAGE_ID)
                .addValue("mailbox", mailbox)
                .addValue("text", text),
        )
    }

    private companion object {
        const val MESSAGE_ID = 124
    }
}
