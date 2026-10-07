package opensamguk.infra.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.RunReason
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.TurnDaemonEvent
import opensamguk.common.wire.TurnDaemonEventEnvelope
import opensamguk.common.wire.encodeCommandPayload
import opensamguk.common.world.WorldId
import opensamguk.infra.persistence.CommandInboxRepository.AcceptedCommand
import opensamguk.infra.persistence.CommandInboxRepository.CommandKind
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Replays the committed reservation between the due-payload read and the real JDBC ring flush. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneralTurnReservationRaceIT {
    private class RacePostgres : PostgreSQLContainer<RacePostgres>("postgres:16-alpine")

    private lateinit var postgres: RacePostgres
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var reservations: ReservedTurnRepository
    private lateinit var inbox: CommandInboxRepository
    private lateinit var results: CommandResultRepository
    private lateinit var executor: JdbcFlushExecutor
    private val world = WorldId(901)
    private val otherWorld = WorldId(902)
    private val ownerId = 9011
    private val mapper = ObjectMapper()
    private val admittedAt = Instant.parse("2026-10-08T00:00:00Z")

    @BeforeAll
    fun startIsolatedDatabase() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — PostgreSQL race reproduction was not executed",
        )
        val nonce = System.getProperty("opensamguk.qaFix.runNonce") ?: UUID.randomUUID().toString()
        postgres = RacePostgres().withLabel("opensamguk.qa-fix.slot0-nonce", nonce)
            .withCreateContainerCmdModifier { command -> command.hostConfig?.withMemory(256L * 1024 * 1024) }
        if (java.lang.Boolean.getBoolean("opensamguk.qaFix.cachedPostgresOnly")) {
            postgres.withImagePullPolicy { false }
        }
        postgres.start()
        val dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        reservations = ReservedTurnRepository(jdbc)
        inbox = CommandInboxRepository(jdbc)
        results = CommandResultRepository(jdbc)
        executor = JdbcFlushExecutor(jdbc, transactions)
    }

    @AfterAll
    fun stopIsolatedDatabase() {
        if (::postgres.isInitialized) postgres.stop()
    }

    @BeforeEach
    fun seedIsolatedWorlds() {
        jdbc.jdbcTemplate.execute("TRUNCATE world_state CASCADE")
        for (id in listOf(world, otherWorld)) {
            jdbc.update(
                """
                INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, config, meta)
                VALUES (:world, 'scenario_1010', 191, 1, 120,
                        '{"worldFormat":"GENERAL_RETAINER_CAMPAIGN"}'::jsonb, '{}'::jsonb)
                """.trimIndent(),
                MapSqlParameterSource("world", id.value),
            )
            for (generalId in listOf(10, 11)) {
                jdbc.update(
                    """
                    INSERT INTO general (world_id, id, name, user_id, npc_state, turn_time)
                    VALUES (:world, :general, :name, :owner, 0, now())
                    """.trimIndent(),
                    MapSqlParameterSource("world", id.value).addValue("general", generalId)
                        .addValue("name", "race-general-$generalId").addValue("owner", ownerId.toString()),
                )
            }
        }
    }

    @Test
    fun `slot0 admitted after an absent snapshot survives the previous turn flush`() {
        val snapshot = reservations.readReserved(world, 10, 0)
        assertFalse(snapshot.rowExists)
        acceptReservation(world, 10, 0, "after-missing", "leadership")
        assertReservation(world, 10, 0, "after-missing", "leadership")

        flushPull(world, 10, snapshot)

        assertAdmissionOnly(world, "after-missing")
        assertReservation(world, 10, 0, "after-missing", "leadership")
    }

    @Test
    fun `replacement B committed after snapshot A survives consumption of the selected turn`() {
        acceptReservation(world, 10, 0, "selected-A", "leadership")
        val snapshot = reservations.readReserved(world, 10, 0)
        assertEquals("selected-A", snapshot.requestId)
        acceptReservation(world, 10, 0, "replacement-B", "strength")
        assertReservation(world, 10, 0, "replacement-B", "strength")

        flushPull(world, 10, snapshot)

        assertAdmissionOnly(world, "selected-A")
        assertAdmissionOnly(world, "replacement-B")
        assertReservation(world, 10, 0, "replacement-B", "strength")
    }

    @Test
    fun `future slot1 shifts to slot0 with its request and arguments intact`() {
        val snapshot = reservations.readReserved(world, 10, 0)
        assertFalse(snapshot.rowExists)
        acceptReservation(world, 10, 1, "future-slot1", "leadership")

        flushPull(world, 10, snapshot)

        assertAdmissionOnly(world, "future-slot1")
        assertReservation(world, 10, 0, "future-slot1", "leadership")
        assertFalse(reservations.readReserved(world, 10, 1).rowExists)
    }

    @Test
    fun `ring consumption leaves another actor and the same actor in another world untouched`() {
        reservations.reserve(world, 10, 0, ReservedTurnRepository.DEFAULT_TURN_ACTION)
        val snapshot = reservations.readReserved(world, 10, 0)
        assertTrue(snapshot.rowExists)
        acceptReservation(world, 11, 0, "other-actor", "leadership")
        acceptReservation(otherWorld, 10, 0, "other-world", "strength")

        flushPull(world, 10, snapshot)

        assertFalse(reservations.readReserved(world, 10, 0).rowExists)
        assertReservation(world, 11, 0, "other-actor", "leadership")
        assertReservation(otherWorld, 10, 0, "other-world", "strength")
        assertAdmissionOnly(world, "other-actor")
        assertAdmissionOnly(otherWorld, "other-world")
    }

    @Test
    fun `replayed consumption cannot rotate the next reservation a second time`() {
        acceptReservation(world, 10, 0, "consumed-A", "leadership")
        acceptReservation(world, 10, 1, "next-B", "strength")
        val selected = reservations.readReserved(world, 10, 0)
        flushPull(world, 10, selected)
        val next = reservations.readReserved(world, 10, 0)
        assertReservation(world, 10, 0, "next-B", "strength")

        flushPull(world, 10, selected)

        assertEquals(next, reservations.readReserved(world, 10, 0))
        assertAdmissionOnly(world, "consumed-A")
        assertAdmissionOnly(world, "next-B")
    }

    @Test
    fun `missing actor cannot admit a reservation`() {
        assertFailsWith<IllegalStateException> {
            reservations.reserve(world, 999, 0, "휴식", "{}")
        }
        assertFalse(reservations.readReserved(world, 999, 0).rowExists)
    }

    @Test
    fun `identical legacy payload rewritten after snapshot keeps its new database identity`() {
        reservations.reserve(world, 10, 0, "휴식", "{}")
        val snapshot = reservations.readReserved(world, 10, 0)
        assertTrue(snapshot.rowExists)
        assertEquals(null, snapshot.requestId)
        reservations.reserve(world, 10, 0, "휴식", "{}")
        val rewritten = reservations.readReserved(world, 10, 0)
        assertEquals(snapshot.actionCode, rewritten.actionCode)
        assertEquals(snapshot.argJson, rewritten.argJson)
        assertNotEquals(snapshot.reservationRevision, rewritten.reservationRevision)

        flushPull(world, 10, snapshot)

        assertEquals(rewritten, reservations.readReserved(world, 10, 0))
    }

    @Test
    fun `concurrent reservation waits between snapshot comparison and ring writes then survives`() {
        acceptReservation(world, 10, 0, "concurrent-A", "leadership")
        val snapshot = reservations.readReserved(world, 10, 0)
        val compared = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val writerEntered = CountDownLatch(1)
        val writerPid = AtomicInteger()
        // Only pause timing after the real SELECT; all returned values and writes remain actual JDBC.
        val pausedJdbc = object : NamedParameterJdbcTemplate(checkNotNull(jdbc.jdbcTemplate.dataSource)) {
            override fun <T : Any?> queryForList(sql: String, params: SqlParameterSource, type: Class<T>): List<T> {
                val values = super.queryForList(sql, params, type)
                if (sql.startsWith("SELECT reservation_revision::text FROM general_turn ")) {
                    compared.countDown()
                    check(proceed.await(15, TimeUnit.SECONDS)) { "comparison pause timeout" }
                }
                return values
            }
        }
        val pausedFlush = JdbcFlushExecutor(pausedJdbc, transactions)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val consuming = pool.submit { flushPull(world, 10, snapshot, pausedFlush) }
            assertTrue(compared.await(15, TimeUnit.SECONDS))
            val writing = pool.submit {
                transactions.executeWithoutResult {
                    writerPid.set(checkNotNull(jdbc.jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                    writerEntered.countDown()
                    acceptReservation(world, 10, 0, "concurrent-B", "strength")
                }
            }
            assertTrue(writerEntered.await(15, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var blocked = false
            while (!blocked && System.nanoTime() < deadline) {
                blocked = jdbc.queryForObject(
                    "SELECT cardinality(pg_blocking_pids(:pid)) > 0",
                    MapSqlParameterSource("pid", writerPid.get()), Boolean::class.java,
                ) == true
                if (!blocked) Thread.sleep(10)
            }
            assertTrue(blocked, "actual PostgreSQL reservation writer must wait on the flush actor lock")
            assertFalse(writing.isDone)
            proceed.countDown()
            consuming.get(15, TimeUnit.SECONDS)
            writing.get(15, TimeUnit.SECONDS)
            assertReservation(world, 10, 0, "concurrent-B", "strength")
            assertAdmissionOnly(world, "concurrent-B")
        } finally {
            proceed.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    private fun acceptReservation(id: WorldId, generalId: Int, slot: Int, requestId: String, stat: String) {
        val command = TurnDaemonCommandEnvelope(
            requestId = requestId, sentAt = admittedAt.toString(),
            command = TurnDaemonCommand.Run(reason = RunReason.POKE),
        )
        val accepted = CommandLifecycleResult(
            type = "reservationAccepted", ok = true, commandKind = CommandKind.RESERVED_TURN.name,
            actionCode = "action.selfTrain", generalId = generalId, turnIdx = slot,
        )
        val admission = TurnDaemonEventEnvelope(
            requestId = requestId, sentAt = admittedAt.toString(),
            event = TurnDaemonEvent.CommandResult(accepted), committedWorldVersion = 0,
        )
        // API-equivalent admission seq1 only; execution effects and seq2 are outside this fixture.
        transactions.executeWithoutResult {
            assertEquals(CommandInboxRepository.InsertResult.Inserted, inbox.insertAccepted(AcceptedCommand(
                worldId = id, requestId = requestId, commandKind = CommandKind.RESERVED_TURN,
                intentFingerprint = "race-$requestId", generalId = generalId, turnIdx = slot,
                actionCode = "action.selfTrain", payloadJson = encodeCommandPayload(command), ownerUserId = ownerId,
            )))
            reservations.reserve(id, generalId, slot, "action.selfTrain", "{\"stat\":\"$stat\"}",
                brief = "수련", requestId = requestId)
            results.insertTerminalResult(id, CommandResultRow(
                requestId = requestId, eventId = "command-result:${id.value}:$requestId:1",
                resultType = accepted.type, ok = true, committedWorldVersion = 0, payloadSchemaVersion = 1,
                envelopeJson = mapper.writeValueAsString(admission),
                sentAt = admittedAt,
            ), expectedInboxStatuses = setOf("ACCEPTED"))
        }
        assertAdmissionOnly(id, requestId)
    }

    private fun flushPull(id: WorldId, generalId: Int, selected: ReservedTurnRepository.ReservedTurn,
        target: JdbcFlushExecutor = executor) {
        // Carries the original read snapshot, as production recorder -> DatabaseHooks now does.
        target.flush(testFlushPayload(
            worldId = id,
            worldStateUpdate = linkedMapOf("id" to id.value, "current_year" to 191, "current_month" to 1),
            reservedGeneralTurnPulls = listOf(GeneralTurnPullRow(generalId, expectedReservation = selected)),
        ))
    }

    private fun assertReservation(id: WorldId, generalId: Int, slot: Int, requestId: String, stat: String) {
        val row = reservations.readReserved(id, generalId, slot)
        assertTrue(row.rowExists, "unexecuted reservation $requestId must remain in its scoped ring")
        assertEquals(requestId, row.requestId)
        assertEquals("action.selfTrain", row.actionCode)
        assertEquals(ownerId, row.reservationOwnerUserId)
        assertEquals(mapper.readTree("{\"stat\":\"$stat\"}"), mapper.readTree(row.argJson))
    }

    private fun assertAdmissionOnly(id: WorldId, requestId: String) {
        val params = MapSqlParameterSource("world", id.value).addValue("request", requestId)
        assertEquals("APPLIED", jdbc.queryForObject(
            "SELECT status FROM command_inbox WHERE world_id=:world AND request_id=:request", params, String::class.java,
        ))
        assertEquals(listOf(1), jdbc.queryForList(
            "SELECT result_seq FROM command_result WHERE world_id=:world AND request_id=:request ORDER BY result_seq",
            params, Int::class.java,
        ))
        assertEquals("reservationAccepted", jdbc.queryForObject(
            "SELECT result_type FROM command_result WHERE world_id=:world AND request_id=:request AND result_seq=1",
            params, String::class.java,
        ))
    }
}
