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
class JdbcBattleActiveSessionReaderIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var store: JdbcBattleSessionStore
    private lateinit var reader: JdbcBattleActiveSessionReader

    @BeforeAll
    fun setup() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — PostgreSQL integration test skipped",
        )
        postgres = PostgreSQLContainer("postgres:16-alpine")
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
        reader = JdbcBattleActiveSessionReader(jdbc)
        for (worldId in 1..2) {
            jdbc.update("""
                INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
                VALUES (:world_id, :scenario, 200, 1, 3600)
            """.trimIndent(), MapSqlParameterSource().addValue("world_id", worldId)
                .addValue("scenario", "battle-active-$worldId"))
        }
    }

    @AfterAll
    fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    @Test
    fun `active rows require world account and general ownership and exclude terminal phase`() {
        val now = Instant.now()
        assertEquals(emptyList(), reader.forOwner(WorldId(1), 42, 7, 100))
        fun ticket(world: Int, id: String, account: Int, general: Int, seconds: Long) =
            FrozenBattleTicket(WorldId(world), id, "{}", sha("{}"), "a".repeat(64),
                "b".repeat(64), "c".repeat(64), 17, 1, 1,
                now.plusSeconds(seconds), now.plusSeconds(seconds + 300),
                listOf(FrozenBattleParticipant(1, account, general, "ATTACKER", 3)))
        assertTrue(store.create(ticket(1, "owned-later", 42, 7, 120)))
        assertTrue(store.create(ticket(1, "owned-first", 42, 7, 60)))
        assertTrue(store.create(ticket(1, "other-account", 99, 7, 30)))
        assertTrue(store.create(ticket(1, "other-general", 42, 8, 40)))
        assertTrue(store.create(ticket(2, "other-world", 42, 7, 20)))

        fun owned() = reader.forOwner(WorldId(1), 42, 7, 100)
        assertEquals(listOf("owned-first", "owned-later"), owned().map { it.battleId })
        assertEquals(listOf("owned-first"), reader.forOwner(WorldId(1), 42, 7, 1).map { it.battleId })
        assertEquals(listOf("other-world"), reader.forOwner(WorldId(2), 42, 7, 100).map { it.battleId })
        assertEquals(listOf("other-account"),
            reader.forOwner(WorldId(1), 99, 7, 100).map { it.battleId })
        assertEquals(listOf("other-general"),
            reader.forOwner(WorldId(1), 42, 8, 100).map { it.battleId })
        assertEquals(3L, owned().first().authorityRevision)
        assertEquals("ATTACKER", owned().first().side)
        assertTrue(owned().first().observedAt.isAfter(now.minusSeconds(5)))

        jdbc.update("""
            UPDATE battle_session SET phase = 'RESULT_PENDING'
             WHERE world_id = 1 AND battle_id = 'owned-first'
        """.trimIndent(), MapSqlParameterSource())
        assertEquals(listOf("owned-first", "owned-later"), owned().map { it.battleId })
        jdbc.update("""
            UPDATE battle_session SET phase = 'RESULT_BLOCKED'
             WHERE world_id = 1 AND battle_id = 'owned-first'
        """.trimIndent(), MapSqlParameterSource())
        assertEquals(listOf("owned-first", "owned-later"), owned().map { it.battleId })
        jdbc.update("""
            UPDATE battle_session SET phase = 'APPLIED'
             WHERE world_id = 1 AND battle_id = 'owned-first'
        """.trimIndent(), MapSqlParameterSource())
        assertEquals(listOf("owned-later"), owned().map { it.battleId })
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
