package opensamguk.infra.persistence

import opensamguk.common.world.WorldId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneralOwnerDeleteFlushIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        val dataSource: DataSource = DriverManagerDataSource().apply {
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
        seed()
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `general owner delete is world scoped and idempotent`() {
        val payload = testFlushPayload(
            worldId = WorldId(1),
            worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 1),
            generalOwnerDeletes = listOf(10, 10),
        )

        executor.flush(payload)
        assertEquals(0, ownerCount(1))
        assertEquals(1, ownerCount(2))
        assertEquals(1, executor.lastOps().single { it.table == "general_owner" }.count)

        executor.flush(payload)
        assertEquals(0, ownerCount(1))
        assertEquals(1, ownerCount(2))
    }

    private fun seed() {
        for (worldId in 1..2) {
            jdbc.update(
                "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (:id, :scenario, 200, 1, 60)",
                MapSqlParameterSource().addValue("id", worldId).addValue("scenario", "owner-$worldId"),
            )
            jdbc.update(
                "INSERT INTO general_owner (world_id, general_id, user_id) VALUES (:world_id, 10, :user_id)",
                MapSqlParameterSource().addValue("world_id", worldId).addValue("user_id", 100L + worldId),
            )
        }
    }

    private fun ownerCount(worldId: Int): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM general_owner WHERE world_id = :world_id AND general_id = 10",
            MapSqlParameterSource().addValue("world_id", worldId),
            Int::class.java,
        ) ?: -1
}
