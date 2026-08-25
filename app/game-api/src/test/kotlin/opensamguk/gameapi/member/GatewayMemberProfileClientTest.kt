package opensamguk.gameapi.member

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GatewayMemberProfileClientTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `maps the four-field gateway response and sends only the service bearer`() {
        val http = HttpServer.create(InetSocketAddress(0), 0)
        server = http
        http.createContext("/internal/users/77/profile") { exchange ->
            assertEquals("Bearer service-token", exchange.requestHeaders.getFirst("Authorization"))
            val body = """{"name":"테스터","grade":6,"picture":"member.png","imageServer":1}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        val client = GatewayMemberProfileClient(
            RestClient.builder().baseUrl("http://127.0.0.1:${http.address.port}").build(),
            "service-token",
        )

        assertEquals(MemberProfile("테스터", 6, "member.png", 1), client.get(77))
    }

    @Test
    fun `maps gateway 404 to an absent profile`() {
        val http = respondingServer(404, "")
        val client = GatewayMemberProfileClient(
            RestClient.builder().baseUrl("http://127.0.0.1:${http.address.port}").build(),
            "service-token",
        )

        assertNull(client.get(88))
    }

    @Test
    fun `maps gateway server failure to profile unavailability`() {
        val http = respondingServer(503, "unavailable")
        val client = GatewayMemberProfileClient(
            RestClient.builder().baseUrl("http://127.0.0.1:${http.address.port}").build(),
            "service-token",
        )

        assertFailsWith<MemberProfileUnavailableException> { client.get(99) }
    }

    @Test
    fun `rejects a structurally invalid successful profile response`() {
        val http = respondingServer(
            200,
            """{"name":" ","grade":99,"picture":null,"imageServer":7}""",
            "application/json",
        )
        val client = GatewayMemberProfileClient(
            RestClient.builder().baseUrl("http://127.0.0.1:${http.address.port}").build(),
            "service-token",
        )

        assertFailsWith<MemberProfileUnavailableException> { client.get(100) }
    }

    private fun respondingServer(status: Int, response: String, contentType: String? = null): HttpServer {
        val http = HttpServer.create(InetSocketAddress(0), 0)
        server = http
        http.createContext("/") { exchange ->
            val body = response.toByteArray(StandardCharsets.UTF_8)
            contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        return http
    }
}
