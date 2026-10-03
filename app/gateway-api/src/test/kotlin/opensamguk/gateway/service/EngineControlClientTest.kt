package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount.once
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineControlClientTest {
    private val mapper = ObjectMapper()
    private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })
    private val origin = "http://sfixture-game-engine:8082"
    private val server = ServerDef("fixture", "Fixture", "http://sfixture-game-api:8081", origin,
        "opensamguk-sfixture", 7)
    private val record get() = """{"serverId":"fixture","origin":"$origin","worldId":991,"generation":7,"revision":"test-revision","credential":"$token"}"""

    @Test
    fun `all controls attach only the selected immutable credential and pins`() {
        for (operation in listOf("pause", "resume", "catch-up")) {
            val builder = RestClient.builder()
            val stub = MockRestServiceServer.bindTo(builder).build()
            val client = EngineControlClient(EngineControlTargets("[$record]", mapper), mapper, builder)
            stub.expect(once(), requestTo("$origin/admin/turn-daemon/$operation"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer $token"))
                .andExpect(header("X-Opensamguk-Control-Server-Id", "fixture"))
                .andExpect(header("X-Opensamguk-Control-World-Id", "991"))
                .andExpect(header("X-Opensamguk-Control-Generation", "7"))
                .andExpect(header("X-Opensamguk-Control-Revision", "test-revision"))
                .andRespond(withSuccess(if (operation == "catch-up")
                    """{"active":true,"multiplier":2,"backlogSeconds":10,"remainingSeconds":5,"initialBacklogSeconds":20,"recoveredSeconds":15,"etaAt":null}"""
                    else """{"paused":true,"changed":true,"statusLabel":"$token","unexpected":"private-upstream-marker"}""",
                    MediaType.APPLICATION_JSON))
            val response = client.post(server, "/admin/turn-daemon/$operation",
                if (operation == "catch-up") mapOf("multiplier" to 2) else null)
            assertEquals(200, response.status)
            assertFalse(response.body.contains(token))
            assertFalse(response.body.contains("private-upstream-marker"))
            stub.verify()
        }
    }

    @Test
    fun `invalid missing duplicate retargeted or changed generation never sends a request`() {
        val variants = listOf("", "[]", "[$record]{}", "[$record,$record]",
            "[${record.replace(origin, "http://other.invalid:8082")} ]",
            "[${record.replace("\"worldId\":991", "\"worldId\":null")} ]",
            "[${record.dropLast(1)},\"unknown\":true}]")
        for (raw in variants) {
            val builder = RestClient.builder()
            val stub = MockRestServiceServer.bindTo(builder).build()
            val client = EngineControlClient(EngineControlTargets(raw, mapper), mapper, builder)
            assertEquals(503, client.post(server, "/admin/turn-daemon/pause").status)
            stub.verify()
        }
        val targets = EngineControlTargets("[$record]", mapper)
        assertNull(targets.resolve(server.copy(generation = null)))
        assertNull(targets.resolve(server.copy(generation = 8)))
        assertNull(targets.resolve(server.copy(gameEngineUrl = "http://other.invalid:8082")))
        assertFalse(targets.toString().contains(token))
        assertFalse(targets.resolve(server).toString().contains(token))
    }

    @Test
    fun `all redirects and upstream errors return safe bodies with no followup`() {
        for (code in listOf(301, 302, 303, 307, 308, 400, 401, 403, 409, 500, 503)) {
            val builder = RestClient.builder()
            val stub = MockRestServiceServer.bindTo(builder).build()
            val client = EngineControlClient(EngineControlTargets("[$record]", mapper), mapper, builder)
            stub.expect(once(), requestTo("$origin/admin/turn-daemon/pause"))
                .andRespond(withStatus(HttpStatus.valueOf(code)).location(java.net.URI("http://other.invalid/secret"))
                    .body("private-upstream-marker $token").contentType(MediaType.APPLICATION_JSON))
            val response = client.post(server, "/admin/turn-daemon/pause")
            assertEquals(when (code) { 401, 403 -> 503; 400, 409, 503 -> code; else -> 502 }, response.status)
            if (code in listOf(401, 403, 503)) {
                assertEquals("ENGINE_CONTROL_UNAVAILABLE", mapper.readTree(response.body).path("code").asText())
            }
            assertFalse(response.body.contains(token))
            assertFalse(response.body.contains("private-upstream-marker"))
            assertFalse(response.body.contains("other.invalid"))
            stub.verify()
        }
    }

    @Test
    fun `unrecognized operations and invalid or oversized success bodies are rejected`() {
        val builder = RestClient.builder()
        val stub = MockRestServiceServer.bindTo(builder).build()
        val client = EngineControlClient(EngineControlTargets("[$record]", mapper), mapper, builder)
        assertEquals(400, client.post(server, "/admin/turn-daemon/status").status)
        for (body in listOf("not-json", "{}", " ".repeat(16385), "{\"paused\":true,\"changed\":true}{}")) {
            stub.expect(requestTo("$origin/admin/turn-daemon/pause"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
        }
        repeat(4) { assertEquals(502, client.post(server, "/admin/turn-daemon/pause").status) }
        stub.verify()
    }

    @Test
    fun `transport failure hides its exception details`() {
        val builder = RestClient.builder()
        val stub = MockRestServiceServer.bindTo(builder).build()
        val client = EngineControlClient(EngineControlTargets("[$record]", mapper), mapper, builder)
        stub.expect(requestTo("$origin/admin/turn-daemon/pause"))
            .andRespond { throw java.net.SocketTimeoutException("private-error-marker $token") }
        val response = client.post(server, "/admin/turn-daemon/pause")
        assertEquals(502, response.status)
        assertFalse(response.body.contains("private-error-marker"))
        assertFalse(response.body.contains(token))
        stub.verify()
    }

    @Test
    fun `internal authentication denial closes its body without reading or retrying`() {
        for (status in listOf(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)) {
            val builder = RestClient.builder()
            val stub = MockRestServiceServer.bindTo(builder).build()
            val client = EngineControlClient(EngineControlTargets("[$record]", mapper), mapper, builder)
            var reads = 0
            var closed = false
            val stream = object : java.io.InputStream() {
                override fun read(): Int { reads++; error("error body must not be read") }
                override fun close() { closed = true }
            }
            stub.expect(once(), requestTo("$origin/admin/turn-daemon/pause")).andRespond {
                object : org.springframework.http.client.ClientHttpResponse {
                    override fun getStatusCode(): org.springframework.http.HttpStatusCode = status
                    override fun getStatusText(): String = status.reasonPhrase
                    override fun getHeaders() = org.springframework.http.HttpHeaders()
                    override fun getBody(): java.io.InputStream = stream
                    override fun close() { stream.close() }
                }
            }
            val response = client.post(server, "/admin/turn-daemon/pause")
            assertEquals(503, response.status)
            assertEquals("ENGINE_CONTROL_UNAVAILABLE", mapper.readTree(response.body).path("code").asText())
            assertEquals(0, reads)
            assertTrue(closed)
            stub.verify()
        }
    }

    @Test
    fun `production DeployService requires its control client bean`() {
        org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withBean(ObjectMapper::class.java, java.util.function.Supplier { mapper })
            .withBean(ServerRegistry::class.java, java.util.function.Supplier { org.mockito.Mockito.mock(ServerRegistry::class.java) })
            .withUserConfiguration(DeployService::class.java)
            .run { context ->
                assertTrue(generateSequence(context.startupFailure) { it.cause }
                    .any { it.message?.contains("EngineControlClient") == true })
            }
    }

    @Test
    fun `production connection preparation disables redirect following`() {
        val factory = EngineControlClient.requestFactory()
        val connection = org.mockito.Mockito.mock(java.net.HttpURLConnection::class.java)
        val prepare = factory.javaClass.getDeclaredMethod("prepareConnection", java.net.HttpURLConnection::class.java, String::class.java)
        prepare.isAccessible = true
        prepare.invoke(factory, connection, "POST")
        org.mockito.Mockito.verify(connection, org.mockito.Mockito.atLeastOnce()).setInstanceFollowRedirects(false)
        org.mockito.Mockito.verify(connection, org.mockito.Mockito.never()).setInstanceFollowRedirects(true)
        org.mockito.Mockito.verify(connection, org.mockito.Mockito.never()).connect()
    }

    @Test
    fun `gateway resolves the canonical default before choosing its dedicated client`() {
        val registry = org.mockito.Mockito.mock(ServerRegistry::class.java)
        val control = org.mockito.Mockito.mock(EngineControlClient::class.java)
        org.mockito.Mockito.`when`(registry.all()).thenReturn(listOf(server))
        org.mockito.Mockito.`when`(control.post(server, "/admin/turn-daemon/pause", null))
            .thenReturn(opensamguk.gateway.dto.EnvProxyResponse(200, "{}"))
        val gateway = DeployService("", "", registry, mapper, control)
        assertEquals(200, gateway.turnDaemonPause(null).status)
        org.mockito.Mockito.verify(control).post(server, "/admin/turn-daemon/pause", null)
        org.mockito.Mockito.clearInvocations(control)
        assertEquals(400, gateway.turnDaemonPause("unknown").status)
        assertEquals(400, gateway.turnDaemonCatchUp("fixture", 3).status)
        org.mockito.Mockito.verifyNoInteractions(control)
    }
}
