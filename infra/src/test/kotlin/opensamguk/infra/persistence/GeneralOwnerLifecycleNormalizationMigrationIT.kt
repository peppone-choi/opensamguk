package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneralOwnerLifecycleNormalizationMigrationIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun startPostgres() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — lifecycle normalization migration IT skipped (not failed)",
        )
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @AfterAll
    fun stopPostgres() {
        if (::postgres.isInitialized) postgres.stop()
    }

    @BeforeEach
    fun resetSchema() {
        jdbc.execute("DROP SCHEMA public CASCADE")
        jdbc.execute("CREATE SCHEMA public")
    }

    @Test
    fun `V39 normalizes only safely correlated stale ownership across worlds`() {
        migrateTo38()
        seedWorld(worldId = 1)
        seedWorld(worldId = 2)

        seedGeneral(worldId = 1, id = 10, userId = "7", npcState = 2)
        seedGeneral(worldId = 1, id = 11, userId = "8", npcState = 3)
        seedGeneral(worldId = 1, id = 12, userId = "9", npcState = 2)
        seedGeneral(worldId = 1, id = 13, userId = "10", npcState = 1)
        seedGeneral(worldId = 1, id = 14, userId = "11", npcState = 2)
        seedGeneral(worldId = 1, id = 15, userId = "12", npcState = 2)
        seedGeneral(worldId = 1, id = 16, userId = "13", npcState = 2)
        seedGeneral(worldId = 1, id = 17, userId = "14", npcState = 2)
        seedOwner(worldId = 1, generalId = 10, userId = 7, requestId = "req-terminal-release")
        seedOwner(worldId = 1, generalId = 11, userId = 8, requestId = null)
        seedOwner(worldId = 1, generalId = 12, userId = 9, requestId = "req-pending")
        seedOwner(worldId = 1, generalId = 13, userId = 10, requestId = "req-live")
        seedOwner(worldId = 1, generalId = 14, userId = 11, requestId = "req-malformed")
        seedOwner(worldId = 1, generalId = 15, userId = 12, requestId = "req-mismatched")
        seedOwner(worldId = 1, generalId = 16, userId = 13, requestId = "req-latest-invalid")
        seedOwner(worldId = 1, generalId = 17, userId = 14, requestId = "req-invalid-optional")
        seedOwner(worldId = 1, generalId = 99, userId = 99, requestId = "req-dead")
        seedTerminalClaimResult(worldId = 1, requestId = "req-terminal-release", generalId = 10)
        seedTerminalClaimResult(worldId = 1, requestId = "req-live", generalId = 13)
        seedTerminalResult(
            worldId = 1,
            requestId = "req-malformed",
            payload = "{}",
        )
        seedTerminalClaimResult(
            worldId = 1,
            requestId = "req-mismatched",
            generalId = 99,
        )
        seedTerminalClaimResult(
            worldId = 1,
            requestId = "req-latest-invalid",
            generalId = 16,
        )
        seedTerminalResult(
            worldId = 1,
            requestId = "req-latest-invalid",
            payload = "{}",
            resultSeq = 2,
        )
        seedTerminalResult(
            worldId = 1,
            requestId = "req-invalid-optional",
            payload = """
                {"requestId":"req-invalid-optional","sentAt":"2026-08-10T00:00:00Z","committedWorldVersion":"not-a-long","event":{"type":"commandResult","result":{"type":"claimNpc","ok":true,"generalId":17,"reason":17}}}
            """.trimIndent(),
        )

        seedGeneral(worldId = 2, id = 10, userId = "70", npcState = 2)
        seedOwner(worldId = 2, generalId = 10, userId = 70, requestId = "req-terminal-release")

        migrateLatest()

        assertNormalizedOwnership()
        migrateLatest()
        assertNormalizedOwnership()
        assertEquals(1, successfulMigrationCount("39"))
    }

    private fun migrateTo38() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(sessionLockConfig)
            .target(MigrationVersion.fromVersion("38"))
            .load()
            .migrate()
    }

    private fun migrateLatest() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(sessionLockConfig)
            .load()
            .migrate()
    }

    private fun assertNormalizedOwnership() {
        assertEquals(null, userIdOf(worldId = 1, generalId = 10))
        assertEquals(null, userIdOf(worldId = 1, generalId = 11))
        assertEquals(null, userIdOf(worldId = 1, generalId = 12))
        assertEquals("10", userIdOf(worldId = 1, generalId = 13))
        assertEquals(null, userIdOf(worldId = 1, generalId = 14))
        assertEquals(null, userIdOf(worldId = 1, generalId = 15))
        assertEquals(null, userIdOf(worldId = 1, generalId = 16))
        assertEquals(null, userIdOf(worldId = 1, generalId = 17))
        assertEquals(null, userIdOf(worldId = 2, generalId = 10))
        assertEquals(0, ownerCount(worldId = 1, generalId = 10))
        assertEquals(0, ownerCount(worldId = 1, generalId = 11))
        assertEquals(1, ownerCount(worldId = 1, generalId = 12), "a request without terminal result remains durable")
        assertEquals(1, ownerCount(worldId = 1, generalId = 13), "a live typed body keeps its durable link")
        assertEquals(1, ownerCount(worldId = 1, generalId = 14), "a malformed result is not safe to repair")
        assertEquals(1, ownerCount(worldId = 1, generalId = 15), "a mismatched result is not safe to repair")
        assertEquals(1, ownerCount(worldId = 1, generalId = 16), "the latest invalid result is not safe to repair")
        assertEquals(1, ownerCount(worldId = 1, generalId = 17), "invalid optional fields are not safe to repair")
        assertEquals(0, ownerCount(worldId = 1, generalId = 99), "a dead general leaves no durable owner link")
        assertEquals(1, ownerCount(worldId = 2, generalId = 10), "a terminal result is world-scoped")
    }

    private fun seedWorld(worldId: Int) {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, meta) VALUES (?, 'migration', 200, 1, 3600, '{}'::jsonb)",
            worldId,
        )
    }

    private fun seedGeneral(worldId: Int, id: Int, userId: String?, npcState: Int) {
        jdbc.update(
            "INSERT INTO general (world_id, id, user_id, name, npc_state, turn_time) VALUES (?, ?, ?, ?, ?, ?)",
            worldId,
            id,
            userId,
            "w${worldId}g$id",
            npcState,
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
        )
    }

    private fun seedOwner(worldId: Int, generalId: Int, userId: Int, requestId: String?) {
        jdbc.update(
            "INSERT INTO general_owner (world_id, general_id, user_id, claimed_at, claim_request_id) VALUES (?, ?, ?, ?, ?)",
            worldId,
            generalId,
            userId,
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
            requestId,
        )
    }

    private fun seedTerminalClaimResult(worldId: Int, requestId: String, generalId: Int, resultSeq: Int = 1) {
        seedTerminalResult(
            worldId = worldId,
            requestId = requestId,
            payload = """
                {"requestId":"$requestId","sentAt":"2026-08-10T00:00:00Z","event":{"type":"commandResult","result":{"type":"claimNpc","ok":true,"generalId":$generalId}},"committedWorldVersion":1}
            """.trimIndent(),
            resultSeq = resultSeq,
        )
    }

    private fun seedTerminalResult(worldId: Int, requestId: String, payload: String, resultSeq: Int = 1) {
        jdbc.update(
            """
            INSERT INTO command_result (
                world_id, request_id, result_seq, terminal_status, result_type, ok,
                committed_world_version, payload_schema_version, result_payload, sent_at
            ) VALUES (?, ?, ?, 'APPLIED', 'claimNpc', true, 1, 1, CAST(? AS jsonb), ?)
            """.trimIndent(),
            worldId,
            requestId,
            resultSeq,
            payload,
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
        )
    }

    private fun userIdOf(worldId: Int, generalId: Int): String? = jdbc.queryForObject(
        "SELECT user_id FROM general WHERE world_id = ? AND id = ?",
        String::class.java,
        worldId,
        generalId,
    )

    private fun ownerCount(worldId: Int, generalId: Int): Int = jdbc.queryForObject(
        "SELECT count(*) FROM general_owner WHERE world_id = ? AND general_id = ?",
        Int::class.java,
        worldId,
        generalId,
    ) ?: 0

    private fun successfulMigrationCount(version: String): Int = jdbc.queryForObject(
        "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success",
        Int::class.java,
        version,
    ) ?: 0

    private companion object {
        private val sessionLockConfig = mapOf("flyway.postgresql.transactional.lock" to "false")
    }
}
