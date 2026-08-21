package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import opensamguk.gateway.dto.EnvProxyResponse
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeployServiceRegistryPersistenceTest {

    private val mapper = ObjectMapper()

    @Test
    fun `successful deployer create is visible in registry without restart`() {
        FakeDeployer().use { deployer ->
            deployer.enqueueMissingOperation()
            deployer.enqueueSucceeded("live1", ",\"name\":\"Live One\"")
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
            deployer.enqueueMissingOperation()
            deployer.enqueueFailed("live1", "rejected")
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
            deployer.enqueueMissingOperation()
            deployer.enqueueSucceeded("live1")
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
            deployer.enqueueMissingOperation()
            deployer.enqueueFailed("live1", "busy")
            val registry = registry("""[{"id":"live1","name":"Live One"}]""")
            val service = DeployService(deployer.url(), "token", registry, mapper)

            val result = service.deleteServer("live1")

            assertEquals(200, result.status)
            assertNotNull(registry.find("live1"))
        }
    }

    @Test
    fun `deployer create success with database failure is truthfully pending and retry repairs without another create`() {
        FakeDeployer().use { deployer ->
            deployer.enqueueMissingOperation()
            deployer.enqueueSucceeded("live1")
            val fixture = registryFixture()
            fixture.jdbc.execute("ALTER TABLE game_server ADD CONSTRAINT reject_live1 CHECK (server_id <> 'live1')")
            val service = DeployService(deployer.url(), "token", fixture.registry, mapper)
            val request = """{"id":"live1","name":"Live One","gameApiPort":"8101","webGamePort":"3101"}"""

            val pending = service.createServer(request)

            assertEquals(202, pending.status)
            assertFalse(mapper.readTree(pending.body).path("ok").asBoolean())
            assertFalse(mapper.readTree(pending.body).path("completed").asBoolean())
            assertTrue(mapper.readTree(pending.body).path("remoteApplied").asBoolean())
            assertFalse(mapper.readTree(pending.body).path("registryApplied").asBoolean())
            assertNull(fixture.registry.find("live1"))

            fixture.jdbc.execute("ALTER TABLE game_server DROP CONSTRAINT reject_live1")
            val repaired = service.createServer(request)

            assertEquals(200, repaired.status)
            assertTrue(mapper.readTree(repaired.body).path("recovered").asBoolean())
            assertNotNull(fixture.registry.find("live1"))
            assertEquals(1, deployer.requests.count { it == "/servers/create" })
        }
    }

    @Test
    fun `deployer close success with database failure is truthfully pending and retry repairs without another close`() {
        FakeDeployer().use { deployer ->
            deployer.enqueueMissingOperation()
            deployer.enqueueSucceeded("live1")
            val fixture = registryFixture("""[{"id":"live1","name":"Live One"}]""")
            fixture.jdbc.execute("CREATE TABLE server_reference (server_id VARCHAR(48) REFERENCES game_server(server_id))")
            fixture.jdbc.update("INSERT INTO server_reference(server_id) VALUES ('live1')")
            val service = DeployService(deployer.url(), "token", fixture.registry, mapper)

            val pending = service.deleteServer("live1")

            assertEquals(202, pending.status)
            assertFalse(mapper.readTree(pending.body).path("ok").asBoolean())
            assertFalse(mapper.readTree(pending.body).path("completed").asBoolean())
            assertTrue(mapper.readTree(pending.body).path("remoteApplied").asBoolean())
            assertFalse(mapper.readTree(pending.body).path("registryApplied").asBoolean())
            assertNotNull(fixture.registry.find("live1"))

            fixture.jdbc.execute("DROP TABLE server_reference")
            val repaired = service.deleteServer("live1")

            assertEquals(200, repaired.status)
            assertTrue(mapper.readTree(repaired.body).path("recovered").asBoolean())
            assertNull(fixture.registry.find("live1"))
            assertEquals(1, deployer.requests.count { it == "/servers/close" })
        }
    }

    @Test
    fun `same server id create race invokes deployer once and leaves one registry row`() {
        FakeDeployer().use { deployer ->
            val requestStarted = CountDownLatch(1)
            val releaseResponse = CountDownLatch(1)
            deployer.enqueueMissingOperation()
            deployer.enqueueBlocking(
                200,
                """{"ok":true,"id":"live1","operationId":"__OPERATION_ID__","operationStatus":"succeeded"}""",
                requestStarted,
                releaseResponse,
            )
            val fixture = registryFixture()
            val registry = fixture.registry
            val firstService = DeployService(deployer.url(), "token", registry, mapper)
            val secondService = DeployService(deployer.url(), "token", ServerRegistry("", mapper, fixture.jdbc), mapper)
            val request = """{"id":"live1","name":"Live One","gameApiPort":"8101","webGamePort":"3101"}"""
            val executor = Executors.newFixedThreadPool(2)

            try {
                val first = executor.submit<EnvProxyResponse> { firstService.createServer(request) }
                assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
                val second = executor.submit<EnvProxyResponse> { secondService.createServer(request) }
                val secondResult = second.get(5, TimeUnit.SECONDS)

                assertEquals(409, secondResult.status)
                releaseResponse.countDown()
                assertEquals(200, first.get(5, TimeUnit.SECONDS).status)
                assertEquals(1, registry.all().size)
                assertEquals(1, deployer.requests.count { it == "/servers/create" })
            } finally {
                releaseResponse.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `restart after crash before create transmission safely sends persisted operation`() {
        FakeDeployer().use { deployer ->
            deployer.enqueue(404, """{"ok":false,"operationId":"__OPERATION_ID__","status":"not_found"}""")
            deployer.enqueue(
                200,
                """{"ok":true,"id":"live1","operationId":"__OPERATION_ID__","operationStatus":"succeeded"}""",
            )
            val fixture = registryFixture()
            val ownerToken = UUID.randomUUID().toString()
            val request = """{"id":"live1","name":"Live One","gameApiPort":"8101","webGamePort":"3101"}"""
            fixture.registry.beginTransition(ServerRegistryTransitionAction.CREATE, serverDef("live1"), ownerToken, request)
            fixture.registry.markDispatched("live1", ServerRegistryTransitionAction.CREATE, ownerToken)
            fixture.jdbc.update("UPDATE game_server_registry_transition SET lease_until = CURRENT_TIMESTAMP")
            val service = DeployService(deployer.url(), "token", fixture.registry, mapper)

            val result = service.createServer(request)

            assertEquals(200, result.status)
            assertNotNull(fixture.registry.find("live1"))
            assertEquals(2, deployer.requests.size)
            val operationPath = deployer.requests.first()
            assertTrue(operationPath.matches(Regex("^/operations/[a-f0-9]{32}$")))
            assertEquals("/servers/create", deployer.requests.last())
            assertEquals(
                operationPath.substringAfterLast('/'),
                mapper.readTree(deployer.requestBodies.last()).path("operationId").asText(),
            )
        }
    }

    @Test
    fun `restart after crash before close transmission safely sends persisted operation`() {
        FakeDeployer().use { deployer ->
            deployer.enqueueMissingOperation()
            deployer.enqueueSucceeded("live1")
            val fixture = registryFixture("""[{"id":"live1","name":"Live One"}]""")
            val server = requireNotNull(fixture.registry.find("live1"))
            val ownerToken = UUID.randomUUID().toString()
            fixture.registry.beginTransition(ServerRegistryTransitionAction.CLOSE, server, ownerToken)
            fixture.registry.markDispatched("live1", ServerRegistryTransitionAction.CLOSE, ownerToken)
            fixture.jdbc.update("UPDATE game_server_registry_transition SET lease_until = CURRENT_TIMESTAMP")
            val service = DeployService(deployer.url(), "token", fixture.registry, mapper)

            val result = service.deleteServer("live1")

            assertEquals(200, result.status)
            assertNull(fixture.registry.find("live1"))
            assertEquals(2, deployer.requests.size)
            val operationPath = deployer.requests.first()
            assertTrue(operationPath.matches(Regex("^/operations/[a-f0-9]{32}$")))
            assertEquals("/servers/close", deployer.requests.last())
            assertEquals(
                operationPath.substringAfterLast('/'),
                mapper.readTree(deployer.requestBodies.last()).path("operationId").asText(),
            )
        }
    }

    @Test
    fun `generic operation 404 from old deployer fails closed without lifecycle replay`() {
        FakeDeployer().use { deployer ->
            deployer.enqueue(404, """{"ok":false,"message":"not found"}""")
            val fixture = registryFixture()
            val ownerToken = UUID.randomUUID().toString()
            val request = """{"id":"live1","name":"Live One","gameApiPort":"8101","webGamePort":"3101"}"""
            fixture.registry.beginTransition(ServerRegistryTransitionAction.CREATE, serverDef("live1"), ownerToken, request)
            fixture.registry.markDispatched("live1", ServerRegistryTransitionAction.CREATE, ownerToken)
            fixture.jdbc.update("UPDATE game_server_registry_transition SET lease_until = CURRENT_TIMESTAMP")
            val restarted = DeployService(deployer.url(), "token", ServerRegistry("", mapper, fixture.jdbc), mapper)

            val result = restarted.createServer(request)

            assertEquals(202, result.status)
            assertEquals(1, deployer.requests.size)
            assertTrue(deployer.requests.single().startsWith("/operations/"))
            assertNull(fixture.registry.find("live1"))
        }
    }

    @Test
    fun `restart refuses to reuse operation key with changed create payload`() {
        FakeDeployer().use { deployer ->
            val fixture = registryFixture()
            val ownerToken = UUID.randomUUID().toString()
            val original = """{"id":"live1","name":"Live One","gameApiPort":"8101","webGamePort":"3101","imageTag":"v1"}"""
            fixture.registry.beginTransition(ServerRegistryTransitionAction.CREATE, serverDef("live1"), ownerToken, original)
            fixture.registry.markDispatched("live1", ServerRegistryTransitionAction.CREATE, ownerToken)
            fixture.jdbc.update("UPDATE game_server_registry_transition SET lease_until = CURRENT_TIMESTAMP")
            val restarted = DeployService(deployer.url(), "token", ServerRegistry("", mapper, fixture.jdbc), mapper)

            val result = restarted.createServer(
                """{"id":"live1","name":"Live One","gameApiPort":"8101","webGamePort":"3101","imageTag":"v2"}""",
            )

            assertEquals(409, result.status)
            assertTrue(deployer.requests.isEmpty())
            assertNull(fixture.registry.find("live1"))
        }
    }

    @Test
    fun `restart after ambiguous create queries terminal result without replay`() {
        FakeDeployer().use { deployer ->
            deployer.enqueueMissingOperation()
            deployer.enqueueRunning("live1")
            deployer.enqueueQueriedTerminal("create", "succeeded", "live1", ok = true)
            val fixture = registryFixture()
            val service = DeployService(deployer.url(), "token", fixture.registry, mapper)
            val request = """{"id":"live1","name":"Live One","gameApiPort":"8101","webGamePort":"3101"}"""

            val pending = service.createServer(request)
            val restarted = DeployService(deployer.url(), "token", ServerRegistry("", mapper, fixture.jdbc), mapper)
            val repaired = restarted.createServer(request)

            assertEquals(202, pending.status)
            assertFalse(mapper.readTree(pending.body).path("ok").asBoolean())
            assertTrue(mapper.readTree(pending.body).path("remoteApplied").isNull)
            assertEquals(200, repaired.status)
            assertNotNull(fixture.registry.find("live1"))
            assertEquals(1, deployer.requests.count { it == "/servers/create" })
            assertEquals(2, deployer.requests.count { it.startsWith("/operations/") })
        }
    }

    @Test
    fun `ambiguous create with unchanged membership remains pending without replay`() {
        FakeDeployer().use { deployer ->
            deployer.enqueueMissingOperation()
            deployer.enqueueRunning("live1")
            deployer.enqueueQueriedPending("create")
            val registry = registry()
            val service = DeployService(deployer.url(), "token", registry, mapper)
            val request = """{"id":"live1","name":"Live One","gameApiPort":"8101","webGamePort":"3101"}"""

            val first = service.createServer(request)
            val second = service.createServer(request)

            assertEquals(202, first.status)
            assertEquals(202, second.status)
            assertNull(registry.find("live1"))
            assertEquals(1, deployer.requests.count { it == "/servers/create" })
            assertEquals(2, deployer.requests.count { it.startsWith("/operations/") })
        }
    }

    @Test
    fun `ambiguous close with unchanged membership remains pending without replay`() {
        FakeDeployer().use { deployer ->
            deployer.enqueueMissingOperation()
            deployer.enqueueRunning("live1")
            deployer.enqueueQueriedPending("close")
            val registry = registry("""[{"id":"live1","name":"Live One"}]""")
            val service = DeployService(deployer.url(), "token", registry, mapper)

            val first = service.deleteServer("live1")
            val second = service.deleteServer("live1")

            assertEquals(202, first.status)
            assertEquals(202, second.status)
            assertNotNull(registry.find("live1"))
            assertEquals(1, deployer.requests.count { it == "/servers/close" })
            assertEquals(2, deployer.requests.count { it.startsWith("/operations/") })
        }
    }

    @Test
    fun `lease reclaim observes dispatched state written before row lock release`() {
        val fixture = registryFixture()
        val firstOwner = UUID.randomUUID().toString()
        fixture.registry.beginTransition(ServerRegistryTransitionAction.CREATE, serverDef("live1"), firstOwner)
        fixture.jdbc.update("UPDATE game_server_registry_transition SET lease_until = CURRENT_TIMESTAMP")
        val secondRegistry = ServerRegistry("", mapper, fixture.jdbc)
        val secondOwner = UUID.randomUUID().toString()
        val executor = Executors.newSingleThreadExecutor()

        fixture.jdbc.dataSource!!.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                "SELECT server_id FROM game_server_registry_transition WHERE server_id = 'live1' FOR UPDATE",
            ).use { statement -> statement.executeQuery().use { assertTrue(it.next()) } }
            val started = CountDownLatch(1)
            val reclaimed = executor.submit<ServerRegistryTransition> {
                started.countDown()
                secondRegistry.beginTransition(ServerRegistryTransitionAction.CREATE, serverDef("live1"), secondOwner)
            }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                connection.prepareStatement(
                    "UPDATE game_server_registry_transition SET dispatched = TRUE WHERE server_id = 'live1' AND owner_token = ?",
                ).use { statement ->
                    statement.setString(1, firstOwner)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.commit()

                val claimed = reclaimed.get(5, TimeUnit.SECONDS)
                assertTrue(claimed.dispatched)
                assertEquals(secondOwner, claimed.ownerToken)
            } finally {
                connection.rollback()
                executor.shutdownNow()
            }
        }
    }

    private fun registry(seedJson: String = ""): ServerRegistry {
        return registryFixture(seedJson).registry
    }

    private fun registryFixture(seedJson: String = ""): RegistryFixture {
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
        jdbc.execute(
            """
            CREATE TABLE game_server_registry_transition (
                server_id VARCHAR(48) PRIMARY KEY,
                action VARCHAR(8) NOT NULL,
                display_name VARCHAR(100) NOT NULL,
                game_api_url VARCHAR(255) NOT NULL,
                game_engine_url VARCHAR(255) NOT NULL,
                deploy_project VARCHAR(100) NOT NULL,
                generation INTEGER,
                scenario_code VARCHAR(100),
                operation_id CHAR(32) NOT NULL UNIQUE,
                request_fingerprint CHAR(64) NOT NULL,
                dispatched BOOLEAN NOT NULL DEFAULT FALSE,
                remote_applied BOOLEAN NOT NULL DEFAULT FALSE,
                owner_token VARCHAR(36) NOT NULL,
                lease_until TIMESTAMP WITH TIME ZONE NOT NULL
            )
            """.trimIndent(),
        )
        return RegistryFixture(ServerRegistry(seedJson, mapper, jdbc), jdbc)
    }

    private data class RegistryFixture(val registry: ServerRegistry, val jdbc: JdbcTemplate)

    private fun serverDef(id: String): ServerDef =
        ServerDef(
            id = id,
            name = "Live One",
            gameApiUrl = "http://s$id-game-api:8081",
            gameEngineUrl = "http://s$id-game-engine:8082",
            deployProject = "opensamguk-s$id",
        )

    private class FakeDeployer : AutoCloseable {
        private data class Response(
            val status: Int,
            val body: String,
            val started: CountDownLatch? = null,
            val release: CountDownLatch? = null,
        )

        private val responses = ArrayDeque<Response>()
        val requests = mutableListOf<String>()
        val requestBodies = mutableListOf<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
        }

        fun url(): String = "http://127.0.0.1:${server.address.port}"

        fun enqueue(status: Int, body: String) {
            responses.add(Response(status, body))
        }

        fun enqueueMissingOperation() {
            enqueue(404, """{"ok":false,"operationId":"__OPERATION_ID__","status":"not_found"}""")
        }

        fun enqueueSucceeded(id: String, extraFields: String = "") {
            enqueue(
                200,
                """{"ok":true,"id":"$id"$extraFields,"operationId":"__OPERATION_ID__","operationStatus":"succeeded"}""",
            )
        }

        fun enqueueFailed(id: String, message: String) {
            enqueue(
                200,
                """{"ok":false,"id":"$id","message":"$message","operationId":"__OPERATION_ID__","operationStatus":"failed"}""",
            )
        }

        fun enqueueRunning(id: String) {
            enqueue(
                202,
                """{"ok":true,"id":"$id","operationId":"__OPERATION_ID__","operationStatus":"running"}""",
            )
        }

        fun enqueueQueriedPending(kind: String) {
            enqueue(
                200,
                """{"operationId":"__OPERATION_ID__","kind":"$kind","status":"running","httpStatus":202,"result":null}""",
            )
        }

        fun enqueueQueriedTerminal(kind: String, status: String, id: String, ok: Boolean) {
            enqueue(
                200,
                """{"operationId":"__OPERATION_ID__","kind":"$kind","status":"$status","httpStatus":200,"result":{"ok":$ok,"id":"$id","operationId":"__OPERATION_ID__","operationStatus":"$status"}}""",
            )
        }

        fun enqueueBlocking(status: Int, body: String, started: CountDownLatch, release: CountDownLatch) {
            responses.add(Response(status, body, started, release))
        }

        private fun handle(exchange: HttpExchange) {
            val response = synchronized(responses) { responses.removeFirstOrNull() } ?: Response(500, "{}")
            val requestBody = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            synchronized(requests) {
                requests += exchange.requestURI.path
                requestBodies += requestBody
            }
            response.started?.countDown()
            response.release?.await(5, TimeUnit.SECONDS)
            val operationId = Regex("""\"operationId\"\s*:\s*\"([a-f0-9]{32})\"""")
                .find(requestBody)
                ?.groupValues
                ?.get(1)
                ?: exchange.requestURI.path.substringAfter("/operations/", "")
            val bytes = response.body.replace("__OPERATION_ID__", operationId).toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        override fun close() {
            server.stop(0)
        }
    }
}
