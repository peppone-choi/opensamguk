package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import opensamguk.gateway.dto.EnvProxyResponse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.net.InetSocketAddress
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `independent services race on PostgreSQL and exactly one reconciles satisfied CREATE`() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable - satisfied CREATE PostgreSQL race IT skipped",
        )
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            val jdbc = JdbcTemplate(dataSource)
            createReconciliationSchema(jdbc)
            val mapper = ObjectMapper()
            val firstRegistry = ServerRegistry("", mapper, jdbc)
            val secondRegistry = ServerRegistry("", mapper, jdbc)
            val target = server("live1").copy(name = "Live One", generation = 2, scenarioCode = "scenario_1010")
            val other = server("live2").copy(name = "Live Two", generation = 3, scenarioCode = "scenario_1020")
            firstRegistry.register(target)
            firstRegistry.register(other)
            insertSatisfiedCreate(jdbc, target, OPERATION_ID)
            insertSatisfiedCreate(jdbc, other, OTHER_OPERATION_ID)
            jdbc.update("INSERT INTO account_fixture (id, username) VALUES (1, 'unchanged-user')")
            val beforeRegistry = firstRegistry.all()

            BlockingMissingDeployer().use { deployer ->
                val firstService = DeployService(deployer.url(), "token", firstRegistry, mapper)
                val secondService = DeployService(deployer.url(), "token", secondRegistry, mapper)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val first = executor.submit<EnvProxyResponse> {
                        firstService.reconcileSatisfiedCreate(
                            "live1",
                            OPERATION_ID,
                            """{"confirm":"RECONCILE CREATE live1"}""",
                        )
                    }
                    assertTrue(deployer.requestStarted.await(10, TimeUnit.SECONDS))
                    val second = executor.submit<EnvProxyResponse> {
                        secondService.reconcileSatisfiedCreate(
                            "live1",
                            OPERATION_ID,
                            """{"confirm":"RECONCILE CREATE live1"}""",
                        )
                    }
                    assertEquals(409, second.get(10, TimeUnit.SECONDS).status)
                    deployer.releaseResponse.countDown()
                    assertEquals(200, first.get(10, TimeUnit.SECONDS).status)

                    assertEquals(1, deployer.requests)
                    assertEquals(beforeRegistry, firstRegistry.all())
                    assertEquals(
                        listOf(OTHER_OPERATION_ID),
                        jdbc.queryForList(
                            "SELECT operation_id FROM game_server_registry_transition ORDER BY operation_id",
                            String::class.java,
                        ).map(String::trim),
                    )
                    assertEquals(
                        mapOf("id" to 1, "username" to "unchanged-user"),
                        jdbc.queryForMap("SELECT id, username FROM account_fixture"),
                    )
                } finally {
                    deployer.releaseResponse.countDown()
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `completion blocked on registered server lock rejects a lease that expires while waiting`() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable - satisfied CREATE PostgreSQL lease-expiry IT skipped",
        )
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            val jdbc = JdbcTemplate(dataSource)
            createReconciliationSchema(jdbc)
            val registry = ServerRegistry("", ObjectMapper(), jdbc)
            val target = server("live1").copy(name = "Live One", generation = 2, scenarioCode = "scenario_1010")
            val other = server("live2").copy(name = "Live Two", generation = 3, scenarioCode = "scenario_1020")
            registry.register(target)
            registry.register(other)
            insertSatisfiedCreate(jdbc, target, OPERATION_ID)
            insertSatisfiedCreate(jdbc, other, OTHER_OPERATION_ID)
            jdbc.update("INSERT INTO account_fixture (id, username) VALUES (1, 'unchanged-user')")
            val beforeRegistry = registry.all()
            val claimed = requireNotNull(registry.claimSatisfiedCreate(OPERATION_ID, "live1", "new-owner"))
            jdbc.update(
                """
                UPDATE game_server_registry_transition
                   SET lease_until = clock_timestamp() + INTERVAL '5 seconds'
                 WHERE operation_id = ?
                """.trimIndent(),
                OPERATION_ID,
            )

            val executor = Executors.newSingleThreadExecutor()
            try {
                dataSource.connection.use { blocker ->
                    blocker.autoCommit = false
                    blocker.prepareStatement(
                        "SELECT server_id FROM game_server WHERE server_id = ? FOR UPDATE",
                    ).use { statement ->
                        statement.setString(1, "live1")
                        statement.executeQuery().use { result -> assertTrue(result.next()) }
                    }

                    val completionStarted = CountDownLatch(1)
                    val completion = executor.submit<Throwable?> {
                        completionStarted.countDown()
                        runCatching { registry.completeSatisfiedCreateReconciliation(claimed) }.exceptionOrNull()
                    }
                    assertTrue(completionStarted.await(5, TimeUnit.SECONDS), "completion did not start")
                    assertTrue(
                        awaitCompletionBlockedOnRegisteredServer(jdbc),
                        "completion did not block on the registered-server row lock",
                    )
                    assertFalse(completion.isDone, "completion finished while the registered-server row was locked")
                    assertTrue(awaitLeaseExpiry(jdbc), "claimed lease did not expire by database wall time")

                    blocker.commit()
                    val failure = completion.get(10, TimeUnit.SECONDS)
                    assertTrue(
                        failure is ServerRegistryTransitionConflict,
                        "expected reconciliation conflict after lease expiry, got $failure",
                    )
                }

                assertEquals(beforeRegistry, registry.all())
                assertEquals(
                    listOf(OPERATION_ID, OTHER_OPERATION_ID),
                    jdbc.queryForList(
                        "SELECT operation_id FROM game_server_registry_transition ORDER BY operation_id",
                        String::class.java,
                    ).map(String::trim),
                )
                assertEquals(
                    mapOf("id" to 1, "username" to "unchanged-user"),
                    jdbc.queryForMap("SELECT id, username FROM account_fixture"),
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun awaitCompletionBlockedOnRegisteredServer(jdbc: JdbcTemplate): Boolean = awaitCondition {
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                  FROM pg_stat_activity
                 WHERE datname = current_database()
                   AND pid <> pg_backend_pid()
                   AND state = 'active'
                   AND wait_event_type = 'Lock'
                   AND query ILIKE '%FROM game_server%'
                   AND query ILIKE '%FOR UPDATE%'
            )
            """.trimIndent(),
            Boolean::class.java,
        ) == true
    }

    private fun awaitLeaseExpiry(jdbc: JdbcTemplate): Boolean = awaitCondition {
        jdbc.queryForObject(
            """
            SELECT lease_until <= clock_timestamp()
              FROM game_server_registry_transition
             WHERE operation_id = ?
            """.trimIndent(),
            Boolean::class.java,
            OPERATION_ID,
        ) == true
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            if (condition()) return true
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
        return false
    }

    private fun createReconciliationSchema(jdbc: JdbcTemplate) {
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
        jdbc.execute(
            """
            CREATE TABLE game_server_registry_transition (
                server_id VARCHAR(48) PRIMARY KEY,
                action VARCHAR(8) NOT NULL,
                display_name TEXT NOT NULL,
                game_api_url TEXT NOT NULL,
                game_engine_url TEXT NOT NULL,
                deploy_project TEXT NOT NULL,
                generation INTEGER,
                scenario_code TEXT,
                operation_id CHAR(32) NOT NULL UNIQUE,
                request_fingerprint CHAR(64) NOT NULL,
                dispatched BOOLEAN NOT NULL DEFAULT FALSE,
                remote_applied BOOLEAN NOT NULL DEFAULT FALSE,
                owner_token VARCHAR(36) NOT NULL,
                lease_until TIMESTAMPTZ NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent(),
        )
        jdbc.execute("CREATE TABLE account_fixture (id INTEGER PRIMARY KEY, username TEXT NOT NULL)")
    }

    private fun insertSatisfiedCreate(jdbc: JdbcTemplate, server: ServerDef, operationId: String) {
        jdbc.update(
            """
            INSERT INTO game_server_registry_transition (
                server_id, action, display_name, game_api_url, game_engine_url, deploy_project,
                generation, scenario_code, operation_id, request_fingerprint,
                dispatched, remote_applied, owner_token, lease_until, created_at
            ) VALUES (?, 'CREATE', ?, ?, ?, ?, ?, ?, ?, ?, TRUE, FALSE, 'old-owner', ?, ?)
            """.trimIndent(),
            server.id,
            server.name,
            server.gameApiUrl,
            server.gameEngineUrl,
            server.deployProject,
            server.generation,
            server.scenarioCode,
            operationId,
            "a".repeat(64),
            Timestamp.from(Instant.now().minusSeconds(60)),
            Timestamp.from(Instant.now().minusSeconds(25 * 60 * 60)),
        )
    }

    private fun server(id: String) = ServerDef(
        id = id,
        name = id,
        gameApiUrl = "http://s$id-game-api:8081",
        gameEngineUrl = "http://s$id-game-engine:8082",
        deployProject = "opensamguk-s$id",
    )

    private class BlockingMissingDeployer : AutoCloseable {
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        @Volatile
        var requests: Int = 0
            private set
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
        }

        fun url(): String = "http://127.0.0.1:${server.address.port}"

        private fun handle(exchange: HttpExchange) {
            requests += 1
            requestStarted.countDown()
            releaseResponse.await(10, TimeUnit.SECONDS)
            val operationId = exchange.requestURI.path.substringAfterLast('/')
            val bytes = """{"ok":false,"operationId":"$operationId","status":"not_found"}"""
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(404, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        override fun close() {
            releaseResponse.countDown()
            server.stop(0)
        }
    }

    companion object {
        private const val OPERATION_ID = "e123456789abcdef0123456789abcdef"
        private const val OTHER_OPERATION_ID = "f123456789abcdef0123456789abcdef"
    }
}
