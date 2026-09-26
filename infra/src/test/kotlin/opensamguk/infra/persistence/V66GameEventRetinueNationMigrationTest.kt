package opensamguk.infra.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventKey
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.GameEvent
import opensamguk.logic.record.OccurredAt
import opensamguk.logic.record.Publication
import opensamguk.logic.record.PublicationState
import opensamguk.logic.record.RefRole
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

class V66GameEventRetinueNationMigrationTest {
    @Test
    fun `retinue event pins its original nation and rejects a missing pin`() {
        assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — V66 migration IT skipped")
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
                .target(MigrationVersion.fromVersion("66")).load().migrate()
            val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
            jdbc.update("INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) " +
                "VALUES (1, 'fixture', 200, 1, 3600)")
            val writer = GameEventWriteRepository(NamedParameterJdbcTemplate(jdbc))
            val event = GameEvent(
                worldId = 1, kind = EventKind.PEOPLE_JOINED, occurredAt = OccurredAt(200, 2, 3, 0),
                audience = AudienceTarget.Retinue(7, 3, setOf(9, 7)),
                publication = Publication(PublicationState.PRIVATE),
                eventKey = EventKey.derive("fixture", "retinue", "1"),
                refs = mapOf(RefRole.PERSON to EventRef.General(9)),
            )
            assertTrue(writer.insert(event))
            assertEquals(3, jdbc.queryForObject("SELECT audience_nation_id FROM game_event WHERE world_id=1",
                Int::class.java))
            assertEquals("{7,9}", jdbc.queryForObject(
                "SELECT recipient_general_ids::text FROM game_event WHERE world_id=1", String::class.java))
            assertTrue(!writer.insert(event.copy(occurredAt = OccurredAt(200, 2, 3, 1))))
            assertFailsWith<IllegalStateException> {
                writer.insert(event.copy(audience = AudienceTarget.Retinue(7, 4, setOf(9, 7))))
            }
            val missingPin = assertFailsWith<DataAccessException> {
                jdbc.update("""INSERT INTO game_event (world_id,event_key,kind,section,audience,
                    audience_general_id,recipient_general_ids,occurred_year,occurred_month,
                    occurred_phase,occurred_ordinal,publication_state)
                    VALUES (1,?,'people.joined','RETINUE_NATION','RETINUE',7,
                    CAST('{7,9}' AS integer[]),200,2,3,2,'PRIVATE')""", "a".repeat(64))
            }
            assertTrue(missingPin.mostSpecificCause.message?.contains("game_event_target_ck") == true)
        }
    }
}
