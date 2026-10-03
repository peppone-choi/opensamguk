package opensamguk.infra.persistence

import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventKey
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.GameEvent
import opensamguk.logic.record.OccurredAt
import opensamguk.logic.record.Publication
import opensamguk.logic.record.PublicationState
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatchUpFinishedMigrationTest {
    @Test
    fun `catch-up public event flushes after upgrade while private payload and targets remain rejected`() {
        assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — catch-up CHECK migration IT skipped")
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            fun migrate(target: String? = null) {
                val configuration = Flyway.configure()
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .locations("classpath:db/migration")
                    .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
                if (target != null) configuration.target(MigrationVersion.fromVersion(target))
                configuration.load().migrate()
            }
            migrate("69")
            val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
            for (worldId in 1..2) {
                jdbc.update("INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (?, 'fixture', 190, 1, 300)", worldId)
            }
            val writer = GameEventWriteRepository(NamedParameterJdbcTemplate(jdbc))
            val event = GameEvent(worldId = 1, kind = EventKind.TURN_CATCH_UP_FINISHED,
                occurredAt = OccurredAt(190, 1, 1, 0), audience = AudienceTarget.Public,
                publication = Publication(PublicationState.PUBLISHED),
                eventKey = EventKey.derive("fixture", "catchUpFinished"))
            val oldError = assertFailsWith<DataAccessException> { writer.insert(event) }
            assertTrue(oldError.mostSpecificCause.message?.contains("game_event_public_ck") == true,
                "R-01 reproduces against pre-fix schema")
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM game_event", Int::class.java))
            migrate()
            assertTrue(writer.insert(event))
            assertTrue(!writer.insert(event), "retry is idempotent")
            assertTrue(writer.insert(event.copy(worldId = 2)), "event key is scoped by world")

            var ordinal = 1
            fun rejected(constraint: String, section: String = "WORLD", state: String = "PUBLISHED",
                         refs: String = "{}", facts: String = "{}", general: Int? = null, nation: Int? = null) {
                val index = ordinal++
                val error = assertFailsWith<DataAccessException> {
                    jdbc.update("""INSERT INTO game_event (world_id, event_key, kind, section, audience,
                        audience_general_id, audience_nation_id, occurred_year, occurred_month, occurred_phase,
                        occurred_ordinal, refs, facts, publication_state)
                        VALUES (1, ?, 'server.catchUpFinished', ?, 'PUBLIC', ?, ?, 190, 1, 1, ?, ?::jsonb, ?::jsonb, ?)""",
                        EventKey.derive("rejected", index.toString()).value, section, general, nation, index, refs, facts, state)
                }
                assertTrue(error.mostSpecificCause.message?.contains(constraint) == true,
                    "expected $constraint, got ${error.mostSpecificCause.message}")
            }
            rejected("game_event_public_ck", refs = """{"ACTOR":7}""")
            rejected("game_event_public_ck", facts = """{"MONEY":900}""")
            rejected("game_event_public_ck", section = "PERSONAL")
            rejected("game_event_publication_ck", state = "PRIVATE")
            rejected("game_event_target_ck", general = 7)
            rejected("game_event_target_ck", nation = 3)
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM game_event WHERE world_id = 1 AND kind = 'server.catchUpFinished'", Int::class.java))
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM game_event WHERE world_id = 2 AND kind = 'server.catchUpFinished'", Int::class.java))
        }
    }
}
