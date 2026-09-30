package opensamguk.infra.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcBattleSessionDiscoveryIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var store: JdbcBattleSessionStore
    private lateinit var discovery: JdbcBattleSessionDiscovery
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
        discovery = JdbcBattleSessionDiscovery(jdbc)
        jdbc.update("""
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (1, 'battle-discovery-it', 200, 1, 3600)
        """.trimIndent(), MapSqlParameterSource())
    }

    @AfterAll
    fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    @Test
    fun `only ready or expired sessions before deadline are claimable`() {
        val now = Instant.now()
        val payload = "{}"
        val sha = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val ticket = FrozenBattleTicket(world, "discovery-it", payload, sha, "a".repeat(64),
            "b".repeat(64), "c".repeat(64), 17, 1, 1, now.plusSeconds(60),
            now.plusSeconds(300), listOf(FrozenBattleParticipant(1, 42, 7, "ATTACKER", 0)))
        assertTrue(store.create(ticket))
        val ref = BattleSessionRef(world, ticket.battleId)
        assertEquals(listOf(ref), discovery.claimable(10))
        val claimed = requireNotNull(store.claimEpoch(world, ticket.battleId, "actor-a", 30_000))
        assertEquals(BattleSessionPhase.JOINING, claimed.phase)
        assertFalse(store.startRun(world, ticket.battleId, "actor-a", claimed.sessionEpoch))
        assertEquals(emptyList(), discovery.claimable(10))
        val params = MapSqlParameterSource().addValue("world_id", world.value)
            .addValue("battle_id", ticket.battleId)
        jdbc.update("""
            UPDATE battle_session SET lease_until = clock_timestamp() - interval '1 second'
             WHERE world_id = :world_id AND battle_id = :battle_id
        """.trimIndent(), params)
        assertEquals(listOf(ref), discovery.claimable(10))
        jdbc.update("""
            UPDATE battle_session SET phase = 'RUNNING'
             WHERE world_id = :world_id AND battle_id = :battle_id
        """.trimIndent(), params)
        assertEquals(listOf(ref), discovery.claimable(10))
        jdbc.update("""
            UPDATE battle_session SET join_deadline_at = clock_timestamp() - interval '2 seconds',
                                      deadline_at = clock_timestamp() - interval '1 second'
             WHERE world_id = :world_id AND battle_id = :battle_id
        """.trimIndent(), params)
        assertEquals(emptyList(), discovery.claimable(10))
        jdbc.update("""
            UPDATE battle_session SET join_deadline_at = clock_timestamp() + interval '1 minute',
                                      deadline_at = clock_timestamp() + interval '2 minutes'
             WHERE world_id = :world_id AND battle_id = :battle_id
        """.trimIndent(), params)
        jdbc.update("""
            UPDATE battle_session SET phase = 'RESULT_PENDING'
             WHERE world_id = :world_id AND battle_id = :battle_id
        """.trimIndent(), params)
        assertEquals(emptyList(), discovery.claimable(10))
    }

    @Test
    fun `NPC session is claimable and starts after wall clock deadlines`() {
        val now = Instant.now()
        val payload = "{}"
        val sha = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val ticket = FrozenBattleTicket(world, "discovery-npc-it", payload, sha, "a".repeat(64),
            "b".repeat(64), "c".repeat(64), 23, 2, 3,
            now.minusSeconds(120), now.minusSeconds(60), emptyList())
        assertTrue(store.create(ticket))
        assertEquals(BattlePacingMode.ACCELERATED_NPC, store.ticket(world, ticket.battleId)?.pacingMode)
        assertTrue(BattleSessionRef(world, ticket.battleId) in discovery.claimable(10))
        val head = requireNotNull(store.claimEpoch(world, ticket.battleId, "npc-actor", 30_000))
        assertTrue(store.startRun(world, ticket.battleId, "npc-actor", head.sessionEpoch))
        assertTrue(store.advanceTick(world, ticket.battleId, "npc-actor", head.sessionEpoch, 0, 1))
        assertTrue(store.advanceResolvedTick(world, ticket.battleId, "npc-actor", head.sessionEpoch, 1, 1))
        assertEquals(BattleSessionPhase.RESOLVING, store.head(world, ticket.battleId)?.phase)
        val resultJson = """{"outcome":"ATTACKER","pacingMode":"ACCELERATED_NPC"}"""
        val resultSha = MessageDigest.getInstance("SHA-256").digest(resultJson.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertTrue(store.publishResult(BattleResultRecord(world, ticket.battleId,
            head.sessionEpoch, "npc-actor", 1, resultJson, resultSha, "e".repeat(64), 2, 3,
            BattlePacingMode.ACCELERATED_NPC)))
        assertEquals(BattlePacingMode.ACCELERATED_NPC,
            store.pendingResults(world, 10).single { it.battleId == ticket.battleId }.pacingMode)
    }
}
