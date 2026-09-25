package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class V65GameEventMigrationTest {
    @Test
    fun `V65 leaves old logs untouched and enforces event isolation and publication`() {
        assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — V65 migration IT skipped")
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            migrate(postgres, "64")
            val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
            jdbc.update("INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'fixture', 200, 1, 3600)")
            jdbc.update("INSERT INTO log_entry (world_id, scope, category, year, month, text) VALUES (1, 'GENERAL', 'ACTION', 199, 12, 'old')")
            migrate(postgres, "65")
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM log_entry WHERE text = 'old'", Int::class.java))
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM game_event", Int::class.java))

            fun insert(key: String, ordinal: Int, audience: String, section: String, kind: String,
                       general: Int? = null, nation: Int? = null, recipients: String? = null,
                       state: String = "PRIVATE", refs: String = "{}", facts: String = "{}") {
                jdbc.update(
                    """INSERT INTO game_event (world_id, event_key, kind, section, audience,
                       audience_general_id, audience_nation_id, recipient_general_ids,
                       occurred_year, occurred_month, occurred_phase, occurred_ordinal, refs, facts, publication_state)
                       VALUES (1, ?, ?, ?, ?, ?, ?, ?::integer[], 200, 2, 3, ?, ?::jsonb, ?::jsonb, ?)""",
                    key, kind, section, audience, general, nation, recipients, ordinal, refs, facts, state,
                )
            }
            val keyA = "a".repeat(64)
            insert(keyA, 0, "PUBLIC", "WORLD", "county.ownerChanged", state = "PUBLISHED",
                refs = """{"CITY":7,"FROM_NATION":0,"TO_NATION":3}""")
            insert("b".repeat(64), 1, "NATION", "RETINUE_NATION", "income.monthly", nation = 3,
                facts = """{"AMOUNT":900}""")
            insert("c".repeat(64), 2, "COURT", "COURT", "court.dispatchReceived", nation = 3,
                recipients = "{5,8}", refs = """{"REQUEST":"dispatch-7"}""")
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM game_event WHERE world_id = 1", Int::class.java))
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM game_event WHERE publication_state = 'PUBLISHED'", Int::class.java))
            insert("5".repeat(64), 3, "RETINUE", "RETINUE_NATION", "people.joined", general = 7,
                recipients = "{7,9}", refs = """{"PERSON":9}""")
            fun rejectedBy(constraint: String, block: () -> Unit) {
                val error = assertFailsWith<DataAccessException> { block() }
                assertTrue(error.mostSpecificCause.message?.contains(constraint) == true,
                    "expected $constraint, got ${error.mostSpecificCause.message}")
            }
            rejectedBy("game_event_key_uq") { insert(keyA, 4, "PUBLIC", "WORLD", "yuedan.announced", state = "PUBLISHED") }
            rejectedBy("game_event_order_uq") { insert("d".repeat(64), 0, "PUBLIC", "WORLD", "yuedan.announced", state = "PUBLISHED") }
            rejectedBy("game_event_public_ck") { insert("e".repeat(64), 4, "PUBLIC", "WORLD", "income.monthly", state = "PUBLISHED") }
            rejectedBy("game_event_public_ck") { insert("3".repeat(64), 4, "PUBLIC", "WORLD", "county.ownerChanged", state = "PUBLISHED", refs = """{"CITY":7,"TO_NATION":3}""") }
            rejectedBy("game_event_public_ck") { insert("4".repeat(64), 4, "PUBLIC", "WORLD", "county.ownerChanged", state = "PUBLISHED", refs = """{"CITY":7,"FROM_NATION":0,"TO_NATION":3,"ACTOR":9}""") }
            rejectedBy("game_event_publication_ck") { insert("f".repeat(64), 4, "NATION", "RETINUE_NATION", "income.monthly", nation = 3, state = "PUBLISHED") }
            rejectedBy("game_event_target_ck") { insert("0".repeat(64), 4, "SELF", "PERSONAL", "personal.applied") }
            rejectedBy("game_event_target_ck") { insert("1".repeat(64), 4, "COURT", "COURT", "court.dispatchReceived", nation = 3, recipients = "{}") }
            rejectedBy("game_event_target_ck") { insert("2".repeat(64), 4, "COURT", "COURT", "court.dispatchReceived", nation = 3, recipients = "{0}") }
            for (index in listOf("game_event_self_feed_idx", "game_event_nation_feed_idx", "game_event_recipient_idx", "game_event_public_feed_idx")) {
                assertTrue(jdbc.queryForObject(
                    "SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = ?",
                    Boolean::class.java, index) == true)
            }
        }
    }

    private fun migrate(postgres: PostgreSQLContainer<*>, target: String) {
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .target(MigrationVersion.fromVersion(target)).load().migrate()
    }
}
