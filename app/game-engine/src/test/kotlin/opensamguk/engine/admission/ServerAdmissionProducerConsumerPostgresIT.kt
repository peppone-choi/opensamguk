package opensamguk.engine.admission

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fail, never skip, when the actual PostgreSQL/HTTP coupling cannot be started. */
class ServerAdmissionProducerConsumerPostgresIT {
    @Test
    fun `actual PostgreSQL PUBLIC then VERIFYING protects writes and terminates the existing HTTP SSE`() = fixture { f, client ->
        assertInternal(f, client, "PUBLIC", "1")
        assertEquals(200, post(client, f.consumerOrigin, f.token()).statusCode())
        assertEquals(1, f.countWrites())
        assertEquals(42L, f.jdbc.queryForObject("SELECT user_id FROM admission_it_write", Long::class.java))
        assertEquals(1, f.downstreamCalls.get())
        stream(client, f.consumerOrigin).use { sse ->
            assertEquals(listOf(":connected"), sse.frame())
            f.relay().fanOut("""{"type":"turnCompleted","fixture":"before-close"}""")
            assertTrue(sse.frame().any { it == "event:turnCompleted" })
            assertEquals(2L, f.enterVerifying())
            assertEquals("VERIFYING", f.jdbc.queryForObject("SELECT state FROM game_server_publication WHERE server_id='pep'", String::class.java))
            assertEquals(2L, f.jdbc.queryForObject("SELECT revision FROM game_server_publication WHERE server_id='pep'", Long::class.java))
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM game_server_publication_operation", Int::class.java))
            assertInternal(f, client, "VERIFYING", "2")
            val listed = get(client, f.gatewayOrigin, "/servers")
            assertEquals(200, listed.statusCode()); assertEquals("no-store", noStore(listed))
            assertTrue(f.mapper.readTree(listed.body()).isArray); assertEquals(0, f.mapper.readTree(listed.body()).size())
            for (role in listOf("USER", "ADMIN")) assertDenied(post(client, f.consumerOrigin, f.token(role)), 403, "SERVER_NOT_PUBLIC")
            assertDenied(post(client, f.consumerOrigin), 401, "AUTH_REQUIRED")
            assertEquals(1, f.countWrites()); assertEquals(1, f.downstreamCalls.get())
            f.relay().fanOut("""{"type":"turnCompleted","fixture":"must-not-send"}""")
            sse.assertEofWithoutData()
            assertEquals(0, f.relay().emitterCount())
        }
        assertEquals(0, f.unexpectedAccountLookups.get())
    }

    @Test
    fun `actual missing PostgreSQL publication yields gateway failure consumer503 zero writes and SSE EOF`() = fixture { f, client ->
        stream(client, f.consumerOrigin).use { sse ->
            assertEquals(listOf(":connected"), sse.frame())
            assertEquals(1, f.jdbc.update("DELETE FROM game_server_publication WHERE server_id='pep'"))
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM game_server WHERE server_id='pep'", Int::class.java))
            val upstream = get(client, f.gatewayOrigin, "/internal/servers/pep/admission", f.serviceToken)
            assertEquals(503, upstream.statusCode()); assertEquals("no-store", noStore(upstream))
            assertEquals("PUBLICATION_SOURCE_UNAVAILABLE", f.mapper.readTree(upstream.body()).path("code").asText())
            for (access in listOf(null, f.token(), f.token("ADMIN"))) {
                assertDenied(post(client, f.consumerOrigin, access), 503, "SERVER_ADMISSION_UNAVAILABLE")
            }
            assertEquals(0, f.countWrites()); assertEquals(0, f.downstreamCalls.get())
            f.relay().fanOut("""{"type":"turnCompleted","fixture":"must-not-send"}""")
            sse.assertEofWithoutData()
            assertEquals(0, f.relay().emitterCount())
            val closed = get(client, f.consumerOrigin, "/sse/turn")
            assertDenied(closed, 503, "SERVER_ADMISSION_UNAVAILABLE")
        }
        assertEquals(0, f.unexpectedAccountLookups.get())
    }

    @Test
    fun `actual service bearer and RSA user verification reject absent or incorrect authentication without downstream writes`() = fixture { f, client ->
        for (service in listOf(null, "incorrect-admission-service")) {
            assertEquals(401, get(client, f.gatewayOrigin, "/internal/servers/pep/admission", service).statusCode())
        }
        assertInternal(f, client, "PUBLIC", "1")
        for (access in listOf(null, "incorrect-user-token")) assertDenied(post(client, f.consumerOrigin, access), 401, "AUTH_REQUIRED")
        assertEquals(0, f.countWrites()); assertEquals(0, f.downstreamCalls.get())
        val misconfigured = f.startConsumer("incorrect-admission-service")
        val origin = AdmissionProducerConsumerFixture.origin(misconfigured)
        assertDenied(post(client, origin, f.token()), 503, "SERVER_ADMISSION_UNAVAILABLE")
        assertDenied(get(client, origin, "/sse/turn"), 503, "SERVER_ADMISSION_UNAVAILABLE")
        assertEquals(0, f.countWrites()); assertEquals(0, f.downstreamCalls.get())
        assertEquals(0, f.relay(misconfigured).emitterCount())
        assertEquals(0, f.unexpectedAccountLookups.get())
    }

    private fun fixture(test: (AdmissionProducerConsumerFixture, HttpClient) -> Unit) {
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            val jdbc = JdbcTemplate(AdmissionProducerConsumerFixture.dataSource(postgres.jdbcUrl, postgres.username, postgres.password))
            AdmissionProducerConsumerFixture.open(jdbc).use { fixture ->
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().use { client ->
                    try { test(fixture, client) }
                    finally { println("ADMISSION_COUPLING_SQL " + fixture.mapper.writeValueAsString(fixture.sqlSnapshot())) }
                }
            }
        }
    }

    private fun assertInternal(f: AdmissionProducerConsumerFixture, client: HttpClient, state: String, revision: String) {
        val response = get(client, f.gatewayOrigin, "/internal/servers/pep/admission", f.serviceToken)
        assertEquals(200, response.statusCode()); assertEquals("no-store", noStore(response))
        val body = f.mapper.readTree(response.body())
        assertEquals(setOf("serverId", "state", "sourceStatus", "revision"), body.fieldNames().asSequence().toSet())
        assertEquals("pep", body.path("serverId").asText()); assertEquals("KNOWN", body.path("sourceStatus").asText())
        assertEquals(state, body.path("state").asText()); assertTrue(body.path("revision").isTextual)
        assertEquals(revision, body.path("revision").asText())
    }

    private fun post(client: HttpClient, origin: String, token: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("$origin/api/command/admission-fixture"))
            .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody())
        token?.let { builder.header("Authorization", "Bearer $it") }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).also {
            println("ADMISSION_COUPLING_HTTP method=POST path=/api/command/admission-fixture status=${it.statusCode()} cache=${noStore(it)}")
        }
    }

    private fun get(client: HttpClient, origin: String, path: String, token: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(origin + path)).timeout(Duration.ofSeconds(5)).GET()
        token?.let { builder.header("Authorization", "Bearer $it") }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).also {
            println("ADMISSION_COUPLING_HTTP method=GET path=$path status=${it.statusCode()} cache=${noStore(it)}")
        }
    }

    private fun assertDenied(response: HttpResponse<String>, status: Int, code: String) {
        assertEquals(status, response.statusCode()); assertEquals("no-store", noStore(response))
        assertEquals(code, com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).path("error").path("code").asText())
    }

    private fun noStore(response: HttpResponse<*>) = response.headers().firstValue("Cache-Control").orElse("")

    private fun stream(client: HttpClient, origin: String): HttpSseProbe {
        val response = client.send(HttpRequest.newBuilder(URI("$origin/sse/turn"))
            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            fail<Nothing>("Actual SSE registration returned ${response.statusCode()}")
        }
        assertEquals("no-store", noStore(response))
        return HttpSseProbe(response.body())
    }

    private class HttpSseProbe(private val stream: InputStream) : AutoCloseable {
        private val reader = stream.bufferedReader()
        private val executor = Executors.newSingleThreadExecutor()
        fun frame(): List<String> = executor.submit<List<String>> {
            val lines = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: error("EOF before expected SSE frame")
                if (line.isEmpty()) break
                lines += line
            }
            lines
        }.get(5, TimeUnit.SECONDS)

        fun assertEofWithoutData() {
            val remainder = executor.submit<List<String>> { reader.lineSequence().toList() }.get(8, TimeUnit.SECONDS)
            assertTrue(remainder.all(String::isBlank), "Closed source must yield HTTP EOF without any later SSE frame: $remainder")
        }

        override fun close() {
            stream.close(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Own SSE reader must terminate")
        }
    }
}
