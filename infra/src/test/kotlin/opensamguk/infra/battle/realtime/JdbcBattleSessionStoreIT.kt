package opensamguk.infra.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer

/** PostgreSQL gate for ticket idempotency, epoch fencing, command receipt, checkpoint and result outbox. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcBattleSessionStoreIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var store: JdbcBattleSessionStore
    private val world = WorldId(1)

    @BeforeAll
    fun setup() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — PostgreSQL integration test skipped",
        )
        postgres.start()
        val ds: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl; username = postgres.username; password = postgres.password
        }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = NamedParameterJdbcTemplate(ds)
        store = JdbcBattleSessionStore(jdbc, ds)
        jdbc.update("""
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (1, 'battle-session-it', 200, 1, 3600)
        """.trimIndent(), MapSqlParameterSource())
    }

    @AfterAll
    fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `committed command survives replay while expired actor cannot checkpoint or resolve`() {
        val now = Instant.now()
        val payload = """{"schemaVersion":1,"battleId":"battle-it"}"""
        val ticket = FrozenBattleTicket(world, "battle-it", payload, sha(payload), "a".repeat(64),
            "b".repeat(64), "c".repeat(64), 17, 4, 2,
            now.minusSeconds(5), now.plusSeconds(300),
            listOf(FrozenBattleParticipant(1, 42, 7, "ATTACKER", 3)))
        assertTrue(store.create(ticket))
        assertFalse(store.create(ticket))
        assertEquals(payload, store.ticket(world, ticket.battleId)?.payloadJson)
        val firstEpoch = assertNotNull(store.claimEpoch(world, ticket.battleId, "actor-a", 30_000))
        assertEquals(BattleSessionPhase.JOINING, firstEpoch.phase)
        assertTrue(store.startRun(world, ticket.battleId, "actor-a", firstEpoch.sessionEpoch))
        val intent = """{"schemaVersion":1,"order":"CHARGE"}"""
        val command = BattleCommandRecord(world, ticket.battleId, 1, "cmd-1", sha(intent),
            firstEpoch.sessionEpoch, 3, 0, "ATTACKER", intent)
        val accepted = (store.admit(command) as CommandAdmission.Receipt).value
        assertEquals(BattleCommandVerdict.ACCEPTED, accepted.verdict)
        assertEquals(2L, accepted.eventSeq)
        assertTrue((store.admit(command) as CommandAdmission.Receipt).value.replayed)
        assertEquals(CommandAdmission.IdempotencyConflict,
            store.admit(command.copy(intentJson = "{}", intentSha256 = sha("{}"))))
        assertNull(store.claimEpoch(world, ticket.battleId, "actor-b", 30_000))
        jdbc.update("""
            UPDATE battle_session SET lease_until = clock_timestamp() - interval '1 second'
             WHERE world_id = 1 AND battle_id = 'battle-it'
        """.trimIndent(), MapSqlParameterSource())
        val secondEpoch = assertNotNull(store.claimEpoch(world, ticket.battleId, "actor-b", 30_000))
        assertEquals(firstEpoch.sessionEpoch + 1, secondEpoch.sessionEpoch)
        assertFalse(store.checkpoint(BattleCheckpoint(world, ticket.battleId, firstEpoch.sessionEpoch,
            "actor-a", 1, accepted.eventSeq!!, "d".repeat(64), byteArrayOf(1))))
        val stale = (store.admit(command.copy(clientCommandId = "cmd-stale")) as CommandAdmission.Receipt).value
        assertEquals("STALE_EPOCH", stale.reasonCode)
        assertTrue((store.admit(command.copy(clientCommandId = "cmd-stale")) as CommandAdmission.Receipt).value.replayed)
        assertTrue(store.checkpoint(BattleCheckpoint(world, ticket.battleId, secondEpoch.sessionEpoch,
            "actor-b", 5, accepted.eventSeq!!, "d".repeat(64), byteArrayOf(1, 2))))
        assertEquals(5, store.latestCheckpoint(world, ticket.battleId)?.tick)
        assertEquals(2, store.eventsAfter(world, ticket.battleId, 0).size)
        val resultJson = """{"outcome":"ATTACKER"}"""
        val result = BattleResultRecord(world, ticket.battleId, secondEpoch.sessionEpoch, "actor-b",
            1, resultJson, sha(resultJson), "e".repeat(64), 4, 2)
        assertTrue(store.publishResult(result))
        assertFalse(store.publishResult(result))
        assertEquals(1, store.pendingResults(world, 10).size)
        assertTrue(store.markApplied(world, ticket.battleId, 1))
        assertFalse(store.markApplied(world, ticket.battleId, 1))
        assertEquals(BattleSessionPhase.APPLIED, store.head(world, ticket.battleId)?.phase)
        assertEquals(3, store.eventsAfter(world, ticket.battleId, 0).size)
        assertTrue((store.admit(command) as CommandAdmission.Receipt).value.replayed)
    }
}
