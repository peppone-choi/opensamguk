package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals

class ServerRegistryPostgresIT {

    @Test
    fun `concurrent registrations receive distinct database order values`() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable - ServerRegistry PostgreSQL IT skipped",
        )
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            val jdbc = JdbcTemplate(dataSource)
            jdbc.execute(
                """
                CREATE TABLE game_server (
                    sort_order BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
                    server_id VARCHAR(48) PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    game_api_url TEXT NOT NULL,
                    game_engine_url TEXT NOT NULL,
                    deploy_project TEXT NOT NULL,
                    generation INTEGER,
                    scenario_code TEXT
                )
                """.trimIndent(),
            )
            val first = ServerRegistry("", ObjectMapper(), jdbc)
            val second = ServerRegistry("", ObjectMapper(), jdbc)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures = listOf(
                    executor.submit {
                        start.await()
                        first.register(server("a1"))
                    },
                    executor.submit {
                        start.await()
                        second.register(server("a2"))
                    },
                )
                start.countDown()
                futures.forEach { it.get() }

                assertEquals(listOf("a1", "a2"), first.all().map { it.id }.sorted())
                assertEquals(
                    2,
                    jdbc.queryForObject("SELECT COUNT(DISTINCT sort_order) FROM game_server", Int::class.java),
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun server(id: String) = ServerDef(
        id = id,
        name = id,
        gameApiUrl = "http://s$id-game-api:8081",
        gameEngineUrl = "http://s$id-game-engine:8082",
        deployProject = "opensamguk-s$id",
    )
}
