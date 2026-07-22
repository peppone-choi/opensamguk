package opensamguk.infra.persistence

import opensamguk.common.world.WorldId
import opensamguk.common.wire.TurnDaemonCommand
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandResultOutboxFlushIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
            .withStartupTimeout(Duration.ofMinutes(3))
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()

        dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()

        jdbc = NamedParameterJdbcTemplate(dataSource)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)))
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, world_version, writer_epoch) VALUES (1, 'sc', 200, 1, 3600, 1, 10)",
            MapSqlParameterSource(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @BeforeEach
    fun resetDbState() {
        jdbc.update("DELETE FROM command_outbox", MapSqlParameterSource())
        jdbc.update("DELETE FROM command_result", MapSqlParameterSource())
        jdbc.update("DELETE FROM command_inbox", MapSqlParameterSource())
        jdbc.update("DELETE FROM general_turn", MapSqlParameterSource())
        jdbc.update(
            "UPDATE world_state SET current_month = 1, world_version = 1, writer_epoch = 10 WHERE id = 1",
            MapSqlParameterSource(),
        )
    }

    @Test
    fun `command terminal result and outbox commit with the state effect`() {
        seedInbox("req-ok", status = "CLAIMED")

        executor.flush(
            testFlushPayload(
                worldId = WorldId(1),
                worldStateUpdate = worldState(month = 2, expectedVersion = 1),
                commandResults = listOf(commandResult("req-ok", committedWorldVersion = 2)),
            ),
        )

        val result = jdbc.queryForMap(
            """
            SELECT terminal_status, result_type, ok, committed_world_version, result_payload::text AS payload
              FROM command_result
             WHERE world_id = 1 AND request_id = 'req-ok'
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        assertEquals("APPLIED", result["terminal_status"])
        assertEquals("tournamentEnroll", result["result_type"])
        assertEquals(true, result["ok"])
        assertEquals(2L, result["committed_world_version"])
        assertTrue(result["payload"].toString().contains("\"requestId\": \"req-ok\""))
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM command_outbox WHERE event_id = 'command-result:1:req-ok:1' AND published_at IS NULL",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
        assertEquals(
            "APPLIED",
            jdbc.queryForObject(
                "SELECT status FROM command_inbox WHERE world_id = 1 AND request_id = 'req-ok'",
                MapSqlParameterSource(),
                String::class.java,
            ),
        )
        val repository = CommandResultRepository(jdbc)
        val pending = repository.findPendingCommandResultOutbox(WorldId(1))
        assertEquals(listOf("command-result:1:req-ok:1"), pending.map { it.eventId })
        assertTrue(pending.single().payloadJson.contains("\"requestId\": \"req-ok\""))
        assertTrue(
            repository.markCommandOutboxPublished(
                worldId = WorldId(1),
                eventId = "command-result:1:req-ok:1",
                publishedAt = Instant.parse("2026-07-22T01:00:00Z"),
            ),
        )
        assertEquals(emptyList(), repository.findPendingCommandResultOutbox(WorldId(1)))
        assertEquals(2, jdbc.queryForObject("SELECT current_month FROM world_state WHERE id = 1", MapSqlParameterSource(), Int::class.java))
    }

    @Test
    fun `command result schema failure rolls back state result outbox and inbox terminal`() {
        seedInbox("req-bad", status = "CLAIMED")
        val version = currentWorldVersion()
        val monthBefore = currentWorldMonth()

        assertFailsWith<Exception> {
            executor.flush(
                testFlushPayload(
                    worldId = WorldId(1),
                    worldStateUpdate = worldState(month = 3, expectedVersion = version),
                    commandResults = listOf(
                        commandResult("req-bad", committedWorldVersion = version + 1, payloadSchemaVersion = 0),
                    ),
                ),
            )
        }

        assertEquals(monthBefore, currentWorldMonth())
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM command_result WHERE world_id = 1 AND request_id = 'req-bad'",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM command_outbox WHERE event_id = 'command-result:1:req-bad:1'",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
        assertEquals(
            "CLAIMED",
            jdbc.queryForObject(
                "SELECT status FROM command_inbox WHERE world_id = 1 AND request_id = 'req-bad'",
                MapSqlParameterSource(),
                String::class.java,
            ),
        )
    }

    @Test
    fun `api-side terminal result writes durable result outbox and terminal inbox atomically`() {
        seedInbox("req-api-terminal", status = "ACCEPTED")
        val repository = CommandResultRepository(jdbc)

        repository.insertTerminalResult(
            worldId = WorldId(1),
            row = commandResult("req-api-terminal", committedWorldVersion = 0),
            expectedInboxStatuses = setOf("ACCEPTED"),
        )

        assertEquals(
            "APPLIED",
            jdbc.queryForObject(
                "SELECT status FROM command_inbox WHERE world_id = 1 AND request_id = 'req-api-terminal'",
                MapSqlParameterSource(),
                String::class.java,
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM command_result WHERE world_id = 1 AND request_id = 'req-api-terminal'",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
        assertEquals(
            listOf("command-result:1:req-api-terminal:1"),
            repository.findPendingCommandResultOutbox(WorldId(1)).map { it.eventId },
        )
    }

    @Test
    fun `reserved execution result coexists with reservation terminal under same request id`() {
        seedInbox("req-reserved-exec", status = "ACCEPTED")
        seedGeneralTurnRing(generalId = 70, requestId = "req-reserved-exec")
        val repository = CommandResultRepository(jdbc)
        repository.insertTerminalResult(
            worldId = WorldId(1),
            row = commandResult(
                "req-reserved-exec",
                committedWorldVersion = 0,
                resultType = "reservationAccepted",
            ),
            expectedInboxStatuses = setOf("ACCEPTED"),
        )
        val version = currentWorldVersion()

        executor.flush(
            testFlushPayload(
                worldId = WorldId(1),
                worldStateUpdate = worldState(month = 4, expectedVersion = version),
                reservedGeneralTurnPulls = listOf(GeneralTurnPullRow(generalId = 70)),
                commandResults = listOf(
                    commandResult(
                        "req-reserved-exec",
                        committedWorldVersion = version + 1,
                        resultSeq = 2,
                        resultType = "executionApplied",
                        terminalizeInbox = false,
                    ),
                ),
            ),
        )

        val results = jdbc.queryForList(
            """
            SELECT result_seq, result_type
              FROM command_result
             WHERE world_id = 1 AND request_id = 'req-reserved-exec'
             ORDER BY result_seq
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        assertEquals(listOf(1, 2), results.map { it["result_seq"] })
        assertEquals(listOf("reservationAccepted", "executionApplied"), results.map { it["result_type"] })
        assertTrue(repository.findResultPayload(WorldId(1), "req-reserved-exec")!!.contains("executionApplied"))
        assertEquals(
            "APPLIED",
            jdbc.queryForObject(
                "SELECT status FROM command_inbox WHERE world_id = 1 AND request_id = 'req-reserved-exec'",
                MapSqlParameterSource(),
                String::class.java,
            ),
        )
        assertEquals(
            listOf("command-result:1:req-reserved-exec:1", "command-result:1:req-reserved-exec:2"),
            repository.findPendingCommandResultOutbox(WorldId(1)).map { it.eventId }.filter { it.contains("req-reserved-exec") },
        )
        assertTrue(
            repository.markCommandOutboxPublished(
                worldId = WorldId(1),
                eventId = "command-result:1:req-reserved-exec:1",
                publishedAt = Instant.parse("2026-07-22T01:01:00Z"),
            ),
        )
        assertTrue(
            repository.markCommandOutboxPublished(
                worldId = WorldId(1),
                eventId = "command-result:1:req-reserved-exec:2",
                publishedAt = Instant.parse("2026-07-22T01:02:00Z"),
            ),
        )
        assertEquals("next-action", readGeneralTurn(70, 0)["action_code"])
        val tail = readGeneralTurn(70, 29)
        assertEquals(ReservedTurnRepository.DEFAULT_TURN_ACTION, tail["action_code"])
        assertEquals(null, tail["request_id"])
    }

    @Test
    fun `reserved ring pull rolls back when execution result outbox flush fails`() {
        seedGeneralTurnRing(generalId = 71, requestId = "req-ring-rollback")
        val version = currentWorldVersion()

        assertFailsWith<Exception> {
            executor.flush(
                testFlushPayload(
                    worldId = WorldId(1),
                    worldStateUpdate = worldState(month = 5, expectedVersion = version),
                    reservedGeneralTurnPulls = listOf(GeneralTurnPullRow(generalId = 71)),
                    commandResults = listOf(
                        commandResult(
                            "req-ring-rollback",
                            committedWorldVersion = version + 1,
                            payloadSchemaVersion = 0,
                            resultSeq = 2,
                            resultType = "executionApplied",
                            terminalizeInbox = false,
                        ),
                    ),
                ),
            )
        }

        assertEquals(version, currentWorldVersion())
        val original = readGeneralTurn(71, 0)
        assertEquals("reserved-action", original["action_code"])
        assertEquals("req-ring-rollback", original["request_id"])
        assertEquals("next-action", readGeneralTurn(71, 1)["action_code"])
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM command_outbox WHERE event_id = 'command-result:1:req-ring-rollback:2'",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )

        executor.flush(
            testFlushPayload(
                worldId = WorldId(1),
                worldStateUpdate = worldState(month = 5, expectedVersion = version),
                reservedGeneralTurnPulls = listOf(GeneralTurnPullRow(generalId = 71)),
                commandResults = listOf(
                    commandResult(
                        "req-ring-rollback",
                        committedWorldVersion = version + 1,
                        resultSeq = 2,
                        resultType = "executionApplied",
                        terminalizeInbox = false,
                    ),
                ),
            ),
        )

        assertEquals(version + 1, currentWorldVersion())
        assertEquals("next-action", readGeneralTurn(71, 0)["action_code"])
        val committedTail = readGeneralTurn(71, 29)
        assertEquals(ReservedTurnRepository.DEFAULT_TURN_ACTION, committedTail["action_code"])
        assertEquals(null, committedTail["request_id"])
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM command_result WHERE request_id = 'req-ring-rollback' AND result_seq = 2",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM command_outbox WHERE event_id = 'command-result:1:req-ring-rollback:2'",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
    }

    @Test
    fun `claimForExecution returns DB payloads in wake order and marks a finite lease`() {
        seedInbox("req-claim-a", generalId = 10)
        seedInbox("req-claim-b", generalId = 20)
        val repository = CommandInboxRepository(jdbc)
        val now = Instant.parse("2026-07-22T00:00:00Z")

        val claimed = repository.claimForExecution(
            worldId = WorldId(1),
            requestIds = listOf("req-claim-b", "missing", "req-claim-a"),
            now = now,
        )

        assertEquals(listOf("req-claim-b", "req-claim-a"), claimed.map { it.requestId })
        assertEquals(20, (claimed[0].envelope.command as TurnDaemonCommand.TroopJoin).generalId)
        assertEquals(10, (claimed[1].envelope.command as TurnDaemonCommand.TroopJoin).generalId)
        val statuses = jdbc.queryForList(
            """
            SELECT request_id, status
              FROM command_inbox
             WHERE request_id IN ('req-claim-a', 'req-claim-b')
             ORDER BY request_id
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        assertEquals(listOf("CLAIMED", "CLAIMED"), statuses.map { it["status"] })
    }

    @Test
    fun `claimPendingForExecution reclaims expired leases only`() {
        seedInbox("req-expired", status = "CLAIMED", generalId = 30)
        seedInbox("req-live", status = "CLAIMED", generalId = 40)
        jdbc.update(
            """
            UPDATE command_inbox
               SET claim_expires_at = CASE request_id
                   WHEN 'req-expired' THEN '2026-07-22T00:00:00Z'::timestamptz
                   ELSE '2026-07-22T00:10:00Z'::timestamptz
               END
             WHERE request_id IN ('req-expired', 'req-live')
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        val repository = CommandInboxRepository(jdbc)

        val claimed = repository.claimPendingForExecution(
            worldId = WorldId(1),
            now = Instant.parse("2026-07-22T00:05:00Z"),
        )

        assertEquals(listOf("req-expired"), claimed.map { it.requestId })
        assertEquals(30, (claimed.single().envelope.command as TurnDaemonCommand.TroopJoin).generalId)
    }

    private fun seedInbox(
        requestId: String,
        status: String = "ACCEPTED",
        generalId: Int = 10,
    ) {
        jdbc.update(
            """
            INSERT INTO command_inbox (
                world_id,
                request_id,
                payload_schema_version,
                command_kind,
                status,
                intent_fingerprint,
                payload
            ) VALUES (
                1,
                :request_id,
                1,
                'IMMEDIATE',
                :status,
                :request_id,
                CAST(:payload AS jsonb)
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("request_id", requestId)
                .addValue("status", status)
                .addValue("payload", commandPayload(requestId, generalId)),
        )
    }

    private fun commandPayload(requestId: String, generalId: Int): String =
        """{"requestId":"$requestId","sentAt":"0200-01-01T00:00:00Z","command":{"type":"troopJoin","requestId":"$requestId","generalId":$generalId,"troopId":7}}"""

    private fun seedGeneralTurnRing(generalId: Int, requestId: String) {
        jdbc.update(
            """
            INSERT INTO general (world_id, id, name, turn_time)
            VALUES (1, :general_id, :name, now())
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("general_id", generalId)
                .addValue("name", "general-$generalId"),
        )
        jdbc.update(
            """
            INSERT INTO general_turn (world_id, general_id, turn_idx, action_code, arg, brief, request_id)
            VALUES
                (1, :general_id, 0, 'reserved-action', '{}'::jsonb, 'reserved-action', :request_id),
                (1, :general_id, 1, 'next-action', '{}'::jsonb, 'next-action', NULL)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("general_id", generalId)
                .addValue("request_id", requestId),
        )
    }

    private fun readGeneralTurn(generalId: Int, turnIdx: Int): Map<String, Any?> =
        jdbc.queryForMap(
            """
            SELECT action_code, request_id
              FROM general_turn
             WHERE world_id = 1 AND general_id = :general_id AND turn_idx = :turn_idx
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("general_id", generalId)
                .addValue("turn_idx", turnIdx),
        )

    private fun worldState(month: Int, expectedVersion: Long): Map<String, Any?> = linkedMapOf(
        "id" to 1,
        "current_year" to 200,
        "current_month" to month,
        "current_phase" to 1,
        "expected_world_version" to expectedVersion,
        "writer_epoch" to 10L,
    )

    private fun currentWorldVersion(): Long =
        jdbc.queryForObject(
            "SELECT world_version FROM world_state WHERE id = 1",
            MapSqlParameterSource(),
            Long::class.java,
        )!!

    private fun currentWorldMonth(): Int =
        jdbc.queryForObject(
            "SELECT current_month FROM world_state WHERE id = 1",
            MapSqlParameterSource(),
            Int::class.java,
        )!!

    private fun commandResult(
        requestId: String,
        committedWorldVersion: Long,
        payloadSchemaVersion: Int = 1,
        resultSeq: Int = 1,
        resultType: String = "tournamentEnroll",
        ok: Boolean = true,
        terminalizeInbox: Boolean = true,
    ): CommandResultRow =
        CommandResultRow(
            requestId = requestId,
            resultSeq = resultSeq,
            eventId = "command-result:1:$requestId:$resultSeq",
            resultType = resultType,
            ok = ok,
            committedWorldVersion = committedWorldVersion,
            payloadSchemaVersion = payloadSchemaVersion,
            envelopeJson = """{"requestId":"$requestId","sentAt":"0200-01-01T00:00:00Z","event":{"type":"commandResult","result":{"type":"$resultType","ok":$ok}}}""",
            sentAt = Instant.parse("0200-01-01T00:00:00Z"),
            terminalizeInbox = terminalizeInbox,
        )
}
