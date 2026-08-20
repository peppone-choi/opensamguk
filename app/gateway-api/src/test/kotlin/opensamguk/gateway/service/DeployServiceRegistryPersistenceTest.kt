package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeployServiceRegistryPersistenceTest {

    private val mapper = ObjectMapper()

    @Test
    fun `successful deployer create is visible in registry without restart`() {
        FakeDeployer().use { deployer ->
            deployer.enqueue(200, """{"ok":true,"id":"live1","name":"Live One"}""")
            val registry = registry()
            val service = DeployService(deployer.url(), "token", registry, mapper)

            val result = service.createServer(
                """{"id":"live1","name":"Live One","generation":"2","scenarioCode":"scenario_1010","gameApiPort":"8101","webGamePort":"3101"}""",
            )

            assertEquals(200, result.status)
            val saved = service.registeredServers().single()
            assertEquals("Live One", saved.name)
            assertEquals(2, saved.generation)
            assertEquals("scenario_1010", saved.scenarioCode)
        }
    }

    @Test
    fun `logical deployer create failure does not register server`() {
        FakeDeployer().use { deployer ->
            deployer.enqueue(200, """{"ok":false,"message":"rejected"}""")
            val registry = registry()
            val service = DeployService(deployer.url(), "token", registry, mapper)

            val result = service.createServer(
                """{"id":"live1","name":"Live One","gameApiPort":"8101","webGamePort":"3101"}""",
            )

            assertEquals(200, result.status)
            assertNull(registry.find("live1"))
        }
    }

    @Test
    fun `successful deployer close removes server from registry without restart`() {
        FakeDeployer().use { deployer ->
            deployer.enqueue(200, """{"ok":true,"id":"live1"}""")
            val registry = registry("""[{"id":"live1","name":"Live One"}]""")
            val service = DeployService(deployer.url(), "token", registry, mapper)

            val result = service.deleteServer("live1")

            assertEquals(200, result.status)
            assertNull(registry.find("live1"))
        }
    }

    @Test
    fun `logical deployer close failure keeps server registered`() {
        FakeDeployer().use { deployer ->
            deployer.enqueue(200, """{"ok":false,"message":"busy"}""")
            val registry = registry("""[{"id":"live1","name":"Live One"}]""")
            val service = DeployService(deployer.url(), "token", registry, mapper)

            val result = service.deleteServer("live1")

            assertEquals(200, result.status)
            assertNotNull(registry.find("live1"))
        }
    }

    private fun registry(seedJson: String = ""): ServerRegistry {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute(
            """
            CREATE TABLE game_server (
                sort_order BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
                server_id VARCHAR(48) PRIMARY KEY,
                display_name VARCHAR(100) NOT NULL,
                game_api_url VARCHAR(255) NOT NULL,
                game_engine_url VARCHAR(255) NOT NULL,
                deploy_project VARCHAR(100) NOT NULL,
                generation INTEGER,
                scenario_code VARCHAR(100)
            )
            """.trimIndent(),
        )
        return ServerRegistry(seedJson, mapper, jdbc)
    }

    private class FakeDeployer : AutoCloseable {
        private val responses = ArrayDeque<Pair<Int, String>>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
        }

        fun url(): String = "http://127.0.0.1:${server.address.port}"

        fun enqueue(status: Int, body: String) {
            responses.add(status to body)
        }

        private fun handle(exchange: HttpExchange) {
            val response = responses.removeFirstOrNull() ?: (500 to "{}")
            exchange.requestBody.use { it.readBytes() }
            val bytes = response.second.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(response.first, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        override fun close() {
            server.stop(0)
        }
    }
}
