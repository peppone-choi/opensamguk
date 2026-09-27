package opensamguk.infra.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
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
            now.plusSeconds(300), emptyList())
        assertTrue(store.create(ticket))
        val ref = BattleSessionRef(world, ticket.battleId)
        assertEquals(listOf(ref), discovery.claimable(10))
        val claimed = requireNotNull(store.claimEpoch(world, ticket.battleId, "actor-a", 30_000))
        assertEquals(BattleSessionPhase.JOINING, claimed.phase)
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
}
