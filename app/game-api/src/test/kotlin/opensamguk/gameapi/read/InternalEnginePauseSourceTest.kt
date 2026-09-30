package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InternalEnginePauseSourceTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val settings = EnginePauseCollectorSettings(URI("http://pep-game-engine:8082"), "pep", 1, 300,
        Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(15))

    @Test
    fun `internal source reads typed current gate without a profile token`() {
        val builder = RestClient.builder().baseUrl(settings.origin.toString())
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://pep-game-engine:8082/admin/turn-daemon/status"))
            .andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess("""{"serverTime":"2026-09-30T11:59:59Z","paused":true,"clock":{"serverId":"pep","worldId":1,"tickSeconds":300}}""", MediaType.APPLICATION_JSON))
        val result = InternalEnginePauseSource(settings, Clock.fixed(now, ZoneOffset.UTC), ObjectMapper(), builder.build()).read()
        assertEquals(true, result?.paused)
        assertEquals("pep", result?.serverId)
        assertEquals(1, result?.worldId)
        assertEquals(now, result?.receivedAt)
        server.verify()
    }

    @Test
    fun `legacy unbound malformed and raw error responses never become a false default`() {
        for (body in listOf("{}", """{"paused":false}""", "not-json",
            """{"serverTime":"2026-09-30T11:59:59Z","paused":false,"clock":{"serverId":"pep","worldId":1,"tickSeconds":5}}""",
            """{"serverTime":"2026-09-30T11:59:59Z","paused":"false","clock":{"serverId":"pep","worldId":1,"tickSeconds":300}}""")) {
            val builder = RestClient.builder().baseUrl(settings.origin.toString())
            val server = MockRestServiceServer.bindTo(builder).build()
            server.expect(requestTo("http://pep-game-engine:8082/admin/turn-daemon/status"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
            assertNull(InternalEnginePauseSource(settings, Clock.fixed(now, ZoneOffset.UTC), ObjectMapper(), builder.build()).read())
            server.verify()
        }
        val builder = RestClient.builder().baseUrl(settings.origin.toString())
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://pep-game-engine:8082/admin/turn-daemon/status"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("private raw error"))
        assertNull(InternalEnginePauseSource(settings, Clock.fixed(now, ZoneOffset.UTC), ObjectMapper(), builder.build()).read())
        server.verify()
    }
}
