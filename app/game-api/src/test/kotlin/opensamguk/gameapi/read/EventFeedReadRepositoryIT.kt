package opensamguk.gameapi.read

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.logic.record.EventSection
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** Red candidates: world, old faction and insufficient permission must fail before projection. */
class EventFeedReadRepositoryIT {
    @Test
    fun `private and public candidates stay in process world and permission`() {
        assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — event feed PG IT skipped")
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
            jdbc.execute("""CREATE TABLE game_event (
                world_id integer NOT NULL, id bigint NOT NULL, kind text NOT NULL, section text NOT NULL,
                audience text NOT NULL, audience_general_id integer, audience_nation_id integer,
                recipient_general_ids integer[], occurred_year integer NOT NULL, occurred_month integer NOT NULL,
                occurred_phase integer NOT NULL, occurred_ordinal integer NOT NULL,
                refs jsonb NOT NULL DEFAULT '{}'::jsonb, facts jsonb NOT NULL DEFAULT '{}'::jsonb,
                publication_state text NOT NULL, publish_after_year integer, publish_after_month integer,
                publish_after_phase integer, PRIMARY KEY (world_id,id))""")
            fun insert(world: Int, id: Long, kind: String, section: String, audience: String,
                       general: Int? = null, nation: Int? = null, recipients: String? = null,
                       published: Boolean = false) {
                jdbc.update("""INSERT INTO game_event (world_id,id,kind,section,audience,
                    audience_general_id,audience_nation_id,recipient_general_ids,occurred_year,
                    occurred_month,occurred_phase,occurred_ordinal,publication_state)
                    VALUES (?,?,?,?,?,?,?,CAST(? AS integer[]),200,1,1,?,?)""",
                    world, id, kind, section, audience, general, nation, recipients, id.toInt(),
                    if (published) "PUBLISHED" else "PRIVATE")
            }
            insert(1, 1, "enlist.joined", "PERSONAL", "SELF", general = 7)
            insert(1, 2, "people.joined", "RETINUE_NATION", "RETINUE", general = 9,
                nation = 3, recipients = "{7,9}")
            insert(1, 3, "income.monthly", "RETINUE_NATION", "NATION", nation = 3)
            insert(1, 4, "people.joined", "RETINUE_NATION", "RETINUE", general = 9,
                nation = 4, recipients = "{7,9}")
            insert(1, 5, "county.ownerChanged", "WORLD", "PUBLIC", published = true)
            insert(2, 6, "people.joined", "RETINUE_NATION", "RETINUE", general = 9,
                nation = 3, recipients = "{7,9}")

            val repository = EventFeedReadRepository(NamedParameterJdbcTemplate(jdbc), GameApiProcessWorld(1))
            assertEquals(listOf(2L), repository.privateCandidates(1, EventSection.RETINUE_NATION,
                7, 3, 1, null, 50).map { it.id })
            assertEquals(listOf(3L, 2L), repository.privateCandidates(1, EventSection.RETINUE_NATION,
                7, 3, 2, null, 50).map { it.id })
            assertEquals(listOf(4L), repository.privateCandidates(1, EventSection.RETINUE_NATION,
                7, 4, 2, null, 50).map { it.id })
            assertEquals(listOf(1L), repository.privateCandidates(1, EventSection.PERSONAL,
                7, 4, 0, null, 50).map { it.id }, "SELF remains readable after transfer")
            assertEquals(listOf(5L), repository.publicCandidates(1, null, 50).map { it.id })
            val first = repository.privateCandidates(1, EventSection.RETINUE_NATION, 7, 3, 2, null, 1).single()
            assertEquals(3L, first.id)
            assertEquals(listOf(2L), repository.privateCandidates(1, EventSection.RETINUE_NATION,
                7, 3, 2, first.position, 50).map { it.id })
            assertTrue(repository.privateCandidates(1, EventSection.RETINUE_NATION,
                8, 3, 4, null, 50).isEmpty(), "new relation cannot add a historical recipient")
        }
    }
}
