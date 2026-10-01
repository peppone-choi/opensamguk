package opensamguk.gameapi.battle.realtime

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import org.mockito.Mockito.*
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class BattleWebSocketAdmissionTest {
    private val token = "BTJ2.abc.${"A".repeat(43)}"
    private val tickets = mock(BattleJoinTicketService::class.java)
    private val generals = mock(GeneralResolver::class.java)
    private val sessions = BattleWebSocketSessions(tickets, generals)
    private val admission = BattleWebSocketAdmission(tickets, GameApiProcessWorld(1), generals,
        "https://game.example", sessions)
    private val handler = BattleWebSocketHandler(sessions, mock(BattleWebSocketProtocol::class.java))
    private val identity = BattleJoinIdentity("pep", WorldId(1), "battle-1", 42, 1, 7,
        "ATTACKER", 1, 3, Instant.parse("2026-09-29T00:01:00Z"))

    private fun attempt(path: String = "/ws/battles/pep/1/battle-1",
                        origin: String? = "https://game.example",
                        protocols: String? = "battle.v1, $token",
                        authorization: String? = null, cookie: String? = null): Pair<Boolean, Pair<MockHttpServletResponse, Map<String, Any>>> {
        val servlet = MockHttpServletRequest("GET", path.substringBefore('?'))
        servlet.queryString = path.substringAfter('?', "").ifEmpty { null }
        if (origin != null) servlet.addHeader("Origin", origin)
        if (protocols != null) servlet.addHeader("Sec-WebSocket-Protocol", protocols)
        if (authorization != null) servlet.addHeader("Authorization", authorization)
        if (cookie != null) servlet.addHeader("Cookie", cookie)
        val response = MockHttpServletResponse()
        val attrs = mutableMapOf<String, Any>()
        val admitted = admission.beforeHandshake(ServletServerHttpRequest(servlet),
            ServletServerHttpResponse(response), handler, attrs)
        return admitted to (response to attrs)
    }

    @Test
    fun `only scoped ticket and current owned general are admitted`() {
        `when`(tickets.verifyBearer(token, "pep", WorldId(1), "battle-1")).thenReturn(identity)
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        val (accepted, result) = attempt(path = "/ws/battles/pep/1/battle-1?lastSeenEventSeq=19")
        assertTrue(accepted)
        assertEquals(identity, result.second[BattleWebSocketAdmission.IDENTITY])
        assertEquals(19L, result.second[BattleWebSocketAdmission.LAST_SEEN_ATTRIBUTE])
        assertEquals(listOf("battle.v1"), handler.subProtocols)
        assertFalse(result.first.headerNames.contains("Sec-WebSocket-Protocol"))
    }

    @Test
    fun `origin long credentials and malformed protocol reject before ticket lookup`() {
        for (result in listOf(
            attempt(origin = null), attempt(origin = "https://other.example"),
            attempt(authorization = "Bearer long-lived"), attempt(cookie = "sam_access=long-lived"),
            attempt(protocols = token), attempt(protocols = "battle.v1, $token, extra"),
            attempt(path = "/ws/battles/pep/1/battle-1?joinTicket=secret"),
            attempt(path = "/ws/battles/pep/1/battle-1?lastSeenEventSeq=-1"),
            attempt(path = "/ws/battles/pep/2/battle-1"),
            attempt(path = "/ws/battles/pep/1/../battle-1"),
        )) {
            assertFalse(result.first)
            assertEquals(HttpStatus.FORBIDDEN.value(), result.second.first.status)
            assertTrue(result.second.second.isEmpty())
        }
        verifyNoInteractions(tickets)
    }

    @Test
    fun `invalid ticket server binding and lost ownership reject before upgrade`() {
        `when`(tickets.verifyBearer(token, "pep", WorldId(1), "battle-1"))
            .thenThrow(SecurityException("invalid battle join ticket"))
        assertFalse(attempt().first)
        reset(tickets)
        `when`(tickets.verifyBearer(token, "pep", WorldId(1), "battle-1")).thenReturn(identity)
        `when`(tickets.verifyBearer(token, "other", WorldId(1), "battle-1"))
            .thenThrow(SecurityException("invalid battle join ticket"))
        `when`(generals.resolveGeneralId(42L)).thenReturn(8)
        assertFalse(attempt().first)
        assertFalse(attempt(path = "/ws/battles/other/1/battle-1").first)
    }

    @Test
    fun `new valid join replaces the previous account and battle reservation`() {
        `when`(tickets.verifyBearer(token, "pep", WorldId(1), "battle-1")).thenReturn(identity)
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        val first = attempt()
        assertTrue(first.first)
        val second = attempt()
        assertTrue(second.first)
        val old = first.second.second[BattleWebSocketSessions.RESERVATION_ATTRIBUTE]
            as BattleWebSocketSessions.Reservation
        val current = second.second.second[BattleWebSocketSessions.RESERVATION_ATTRIBUTE]
            as BattleWebSocketSessions.Reservation
        val staleSocket = mock(org.springframework.web.socket.WebSocketSession::class.java)
        assertFalse(sessions.attach(old, staleSocket))
        sessions.release(old)
        assertTrue(sessions.attach(current, mock(org.springframework.web.socket.WebSocketSession::class.java)))
    }
}
