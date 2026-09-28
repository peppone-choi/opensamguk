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
        assertFalse(store.advanceTick(world, ticket.battleId, "actor-a", firstEpoch.sessionEpoch, 0, 1))
        assertTrue(store.advanceTick(world, ticket.battleId, "actor-a", firstEpoch.sessionEpoch, 0, 2))
        assertEquals(1, store.head(world, ticket.battleId)?.currentTick)
        assertFalse(store.advanceTick(world, ticket.battleId, "actor-a", firstEpoch.sessionEpoch, 0, 2))
        assertTrue((store.admit(command) as CommandAdmission.Receipt).value.replayed)
        assertEquals(CommandAdmission.IdempotencyConflict,
            store.admit(command.copy(intentJson = "{}", intentSha256 = sha("{}"))))
        val raced = (store.admit(command.copy(clientCommandId = "cmd-race", issuedTick = 1))
            as CommandAdmission.Receipt).value
        assertEquals(3L, raced.eventSeq)
        assertFalse(store.advanceTick(world, ticket.battleId, "actor-a", firstEpoch.sessionEpoch, 1, 2))
        assertTrue(store.advanceTick(world, ticket.battleId, "actor-a", firstEpoch.sessionEpoch, 1, 3))
        val joinPayload = """{"side":"ATTACKER"}"""
        assertEquals(4L, store.appendTransition(BattleTransition(world, ticket.battleId,
            firstEpoch.sessionEpoch, "actor-a", "join-after-start", "HUMAN_JOIN", 1,
            2, 3, joinPayload, sha(joinPayload))))
        assertFalse(store.advanceTick(world, ticket.battleId, "actor-a", firstEpoch.sessionEpoch, 2, 3))
        assertTrue(store.advanceTick(world, ticket.battleId, "actor-a", firstEpoch.sessionEpoch, 2, 4))
        assertEquals(3, store.head(world, ticket.battleId)?.currentTick)
        assertNull(store.claimEpoch(world, ticket.battleId, "actor-b", 30_000))
        jdbc.update("""
            UPDATE battle_session SET lease_until = clock_timestamp() - interval '1 second'
             WHERE world_id = 1 AND battle_id = 'battle-it'
        """.trimIndent(), MapSqlParameterSource())
        assertFalse(store.advanceTick(world, ticket.battleId, "actor-a", firstEpoch.sessionEpoch, 3, 4))
        val secondEpoch = assertNotNull(store.claimEpoch(world, ticket.battleId, "actor-b", 30_000))
        assertEquals(firstEpoch.sessionEpoch + 1, secondEpoch.sessionEpoch)
        assertFalse(store.checkpoint(BattleCheckpoint(world, ticket.battleId, firstEpoch.sessionEpoch,
            "actor-a", 3, 4, "d".repeat(64), byteArrayOf(1))))
        val stale = (store.admit(command.copy(clientCommandId = "cmd-stale")) as CommandAdmission.Receipt).value
        assertEquals("STALE_EPOCH", stale.reasonCode)
        assertTrue((store.admit(command.copy(clientCommandId = "cmd-stale")) as CommandAdmission.Receipt).value.replayed)
        assertFalse(store.checkpoint(BattleCheckpoint(world, ticket.battleId, secondEpoch.sessionEpoch,
            "actor-b", 50, 4, "d".repeat(64), byteArrayOf(1, 2))))
        (3 until 50).forEach { tick ->
            assertTrue(store.advanceTick(world, ticket.battleId, "actor-b", secondEpoch.sessionEpoch, tick, 4))
        }
        assertEquals(50, store.head(world, ticket.battleId)?.currentTick)
        assertTrue(store.checkpoint(BattleCheckpoint(world, ticket.battleId, secondEpoch.sessionEpoch,
            "actor-b", 50, 4, "d".repeat(64), byteArrayOf(1, 2))))
        assertFalse(store.checkpoint(BattleCheckpoint(world, ticket.battleId, secondEpoch.sessionEpoch,
            "actor-b", 50, 3, "d".repeat(64), byteArrayOf(1, 2))))
        assertEquals(50, store.latestCheckpoint(world, ticket.battleId)?.tick)
        assertEquals(4, store.eventsAfter(world, ticket.battleId, 0).size)
        val resultJson = """{"outcome":"ATTACKER"}"""
        val result = BattleResultRecord(world, ticket.battleId, secondEpoch.sessionEpoch, "actor-b",
            1, resultJson, sha(resultJson), "e".repeat(64), 4, 2)
        assertTrue(store.publishResult(result))
        assertFalse(store.publishResult(result))
        assertEquals(1, store.pendingResults(world, 10).size)
        assertTrue(store.markApplied(world, ticket.battleId, 1))
        assertFalse(store.markApplied(world, ticket.battleId, 1))
        assertEquals(BattleSessionPhase.APPLIED, store.head(world, ticket.battleId)?.phase)
        assertEquals(5, store.eventsAfter(world, ticket.battleId, 0).size)
        assertTrue((store.admit(command) as CommandAdmission.Receipt).value.replayed)
    }

    @Test
    fun `observed tick lag window accepts recent commands and rejects old or future ticks`() {
        val now = Instant.now()
        val payload = """{"schemaVersion":1,"battleId":"battle-lag-it"}"""
        val ticket = FrozenBattleTicket(world, "battle-lag-it", payload, sha(payload), "a".repeat(64),
            "b".repeat(64), "c".repeat(64), 18, 5, 3,
            now.minusSeconds(5), now.plusSeconds(300),
            listOf(FrozenBattleParticipant(1, 43, 8, "ATTACKER", 4)))
        assertTrue(store.create(ticket))
        val epoch = assertNotNull(store.claimEpoch(world, ticket.battleId, "actor-lag", 30_000))
        assertTrue(store.startRun(world, ticket.battleId, "actor-lag", epoch.sessionEpoch))
        (0 until 11).forEach { tick ->
            assertTrue(store.advanceTick(world, ticket.battleId, "actor-lag", epoch.sessionEpoch, tick, 1))
        }
        val intent = """{"order":"HOLD"}"""
        val command = BattleCommandRecord(world, ticket.battleId, 1, "cmd-lag-one", sha(intent),
            epoch.sessionEpoch, 4, 10, "ATTACKER", intent)
        val oneTickOld = (store.admit(command) as CommandAdmission.Receipt).value
        assertEquals(BattleCommandVerdict.ACCEPTED, oneTickOld.verdict)
        assertEquals(12, oneTickOld.effectiveTick)
        val atWindow = (store.admit(command.copy(clientCommandId = "cmd-lag-boundary", issuedTick = 1))
            as CommandAdmission.Receipt).value
        assertEquals(BattleCommandVerdict.ACCEPTED, atWindow.verdict)
        val tooOld = (store.admit(command.copy(clientCommandId = "cmd-lag-old", issuedTick = 0))
            as CommandAdmission.Receipt).value
        assertEquals("STALE_TICK", tooOld.reasonCode)
        val future = (store.admit(command.copy(clientCommandId = "cmd-lag-future", issuedTick = 12))
            as CommandAdmission.Receipt).value
        assertEquals("STALE_TICK", future.reasonCode)
    }

    @Test
    fun `expired running session can be claimed to publish timeout result but cannot advance`() {
        val now = Instant.now()
        val payload = """{"schemaVersion":1,"battleId":"battle-expired-it"}"""
        val ticket = FrozenBattleTicket(world, "battle-expired-it", payload, sha(payload), "a".repeat(64),
            "b".repeat(64), "c".repeat(64), 19, 6, 4,
            now.minusSeconds(5), now.plusSeconds(300),
            listOf(FrozenBattleParticipant(1, 44, 9, "ATTACKER", 5)))
        assertTrue(store.create(ticket))
        val first = assertNotNull(store.claimEpoch(world, ticket.battleId, "actor-expired-a", 30_000))
        assertTrue(store.startRun(world, ticket.battleId, "actor-expired-a", first.sessionEpoch))
        jdbc.update("""
            UPDATE battle_session
               SET deadline_at = clock_timestamp() - interval '1 second',
                   lease_until = clock_timestamp() - interval '1 second'
             WHERE world_id = 1 AND battle_id = 'battle-expired-it'
        """.trimIndent(), MapSqlParameterSource())
        val second = assertNotNull(store.claimEpoch(world, ticket.battleId, "actor-expired-b", 30_000))
        assertEquals(first.sessionEpoch + 1, second.sessionEpoch)
        assertEquals(BattleSessionPhase.RUNNING, second.phase)
        assertFalse(store.advanceTick(world, ticket.battleId, "actor-expired-b", second.sessionEpoch, 0, 1))
        val resultJson = """{"outcome":"TIMEOUT_SCORE"}"""
        val result = BattleResultRecord(world, ticket.battleId, second.sessionEpoch, "actor-expired-b",
            1, resultJson, sha(resultJson), "f".repeat(64), 6, 4)
        assertTrue(store.publishResult(result))
        assertEquals(BattleSessionPhase.RESULT_PENDING, store.head(world, ticket.battleId)?.phase)
        assertTrue(store.pendingResults(world, 10).any { it.battleId == ticket.battleId })
    }
}
