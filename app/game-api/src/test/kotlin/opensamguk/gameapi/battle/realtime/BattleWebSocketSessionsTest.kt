package opensamguk.gameapi.battle.realtime

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.security.ServerAdmissionDecision
import opensamguk.gameapi.security.ServerAdmissionPolicy
import opensamguk.gameapi.security.ServerAdmissionRead
import opensamguk.gameapi.security.ServerAdmissionSnapshot
import opensamguk.gameapi.security.ServerAdmissionSource
import opensamguk.gameapi.security.ServerPublicationState
import org.mockito.Mockito.*
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.WebSocketSession

class BattleWebSocketSessionsTest {
    private val nowNanos = AtomicLong(0)
    private val tickets = mock(BattleJoinTicketService::class.java)
    private val generals = mock(GeneralResolver::class.java)
    private var publicationState: ServerPublicationState? = ServerPublicationState.PUBLIC
    private var publicationRevision = 1L
    private var publicationReads = 0
    private var localCapacity = false
    private val publication = ServerAdmissionPolicy(ServerAdmissionSource {
        publicationReads++
        if (localCapacity) return@ServerAdmissionSource ServerAdmissionRead.LocalCapacity
        publicationState?.let { ServerAdmissionRead.Known(
            ServerAdmissionSnapshot("pep", it, publicationRevision), nowNanos.get(), Duration.ofSeconds(2).toNanos(),
        ) } ?: ServerAdmissionRead.Unavailable
    }, nowNanos::get)
    private val sessions = BattleWebSocketSessions(tickets, generals, publication, nowNanos::get,
        Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(60))
    private val identity = BattleJoinIdentity("pep", WorldId(1), "battle-1", 42, 1, 7,
        "ATTACKER", 1, 3, Instant.parse("2026-09-29T00:01:00Z"))

    private fun socket(reservation: BattleWebSocketSessions.Reservation): WebSocketSession =
        mock(WebSocketSession::class.java).also { session ->
            `when`(session.attributes).thenReturn(mutableMapOf<String, Any>(
                BattleWebSocketSessions.RESERVATION_ATTRIBUTE to reservation))
            `when`(session.isOpen).thenReturn(true)
        }

    private fun allowCurrent() {
        `when`(tickets.isCurrent(identity)).thenReturn(true)
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
    }

    @Test
    fun `stale pending reservation expires before it can attach`() {
        val pending = sessions.reserve(identity)
        nowNanos.set(Duration.ofSeconds(10).toNanos())
        sessions.sweep()
        assertFalse(sessions.attach(pending, socket(pending)))
        val current = sessions.reserve(identity)
        assertTrue(sessions.attach(current, socket(current)))
    }

    @Test
    fun `new join replaces an active socket and fences its old reservation`() {
        val old = sessions.reserve(identity)
        val oldSocket = socket(old)
        assertTrue(sessions.attach(old, oldSocket))
        val current = sessions.reserve(identity)
        verify(oldSocket).close(CloseStatus.GOING_AWAY)
        assertFalse(sessions.attach(old, socket(old)))
        sessions.release(old)
        assertTrue(sessions.attach(current, socket(current)))
    }

    @Test
    fun `handler close callback releases its connection slot`() {
        val reservation = sessions.reserve(identity)
        val session = socket(reservation)
        val handler = BattleWebSocketHandler(sessions)
        handler.afterConnectionEstablished(session)
        handler.afterConnectionClosed(session, CloseStatus.NORMAL)
        sessions.sweep()
        verifyNoInteractions(tickets, generals)
        val current = sessions.reserve(identity)
        assertTrue(sessions.attach(current, socket(current)))
    }

    @Test
    fun `stale battle authority closes the socket and frees its slot`() {
        val reservation = sessions.reserve(identity)
        val session = socket(reservation)
        assertTrue(sessions.attach(reservation, session))
        allowCurrent()
        sessions.sweep()
        verify(session, never()).close(any(CloseStatus::class.java))
        `when`(tickets.isCurrent(identity)).thenReturn(false)
        sessions.sweep()
        verify(session).close(CloseStatus.POLICY_VIOLATION)
        val current = sessions.reserve(identity)
        assertTrue(sessions.attach(current, socket(current)))
    }

    @Test
    fun `lost current general closes even when battle lease remains valid`() {
        val reservation = sessions.reserve(identity)
        val session = socket(reservation)
        assertTrue(sessions.attach(reservation, session))
        allowCurrent()
        `when`(generals.resolveGeneralId(42L)).thenReturn(8)
        sessions.sweep()
        verify(session).close(CloseStatus.POLICY_VIOLATION)
    }

    @Test
    fun `battle store failure closes the socket rather than retaining stale authority`() {
        val reservation = sessions.reserve(identity)
        val session = socket(reservation)
        assertTrue(sessions.attach(reservation, session))
        `when`(tickets.isCurrent(identity)).thenThrow(IllegalStateException("battle store unavailable"))
        sessions.sweep()
        verify(session).close(CloseStatus.POLICY_VIOLATION)
    }

    @Test
    fun `verifying between handshake and attach rejects and releases pending slot`() {
        val pending = sessions.reserve(identity)
        publicationState = ServerPublicationState.VERIFYING
        publicationRevision = 2
        assertFalse(sessions.attach(pending, socket(pending)))
        publicationState = ServerPublicationState.PUBLIC
        publicationRevision = 3
        assertFalse(sessions.attach(pending, socket(pending)))
    }

    @Test
    fun `one unavailable publication read revokes every active and pending slot`() {
        val active = sessions.reserve(identity)
        val activeSocket = socket(active)
        assertTrue(sessions.attach(active, activeSocket))
        val pendingIdentity = identity.copy(accountId = 43, participantId = 2)
        val pending = sessions.reserve(pendingIdentity)
        val readsBeforeSweep = publicationReads
        publicationState = null
        sessions.sweep()
        assertTrue(publicationReads == readsBeforeSweep + 1)
        verify(activeSocket).close(CloseStatus.POLICY_VIOLATION)
        publicationState = ServerPublicationState.PUBLIC
        publicationRevision = 2
        assertFalse(sessions.attach(pending, socket(pending)))
    }

    @Test
    fun `local capacity keeps only a still current observed socket and never refreshes its deadline`() {
        val active = sessions.reserve(identity)
        val activeSocket = socket(active)
        assertTrue(sessions.attach(active, activeSocket))
        allowCurrent()
        localCapacity = true
        nowNanos.set(Duration.ofSeconds(1).toNanos())
        val readsBeforeSweep = publicationReads
        sessions.sweep()
        assertTrue(publicationReads == readsBeforeSweep + 1)
        verify(activeSocket, never()).close(any(CloseStatus::class.java))
        verifyNoInteractions(tickets, generals)
        val pending = sessions.reserve(identity.copy(accountId = 43, participantId = 2))
        assertFalse(sessions.attach(pending, socket(pending)))
        nowNanos.set(Duration.ofSeconds(2).toNanos())
        sessions.sweep()
        verify(activeSocket).close(CloseStatus.POLICY_VIOLATION)
        localCapacity = false
        assertFalse(sessions.attach(pending, socket(pending)))
        assertFalse(sessions.attach(active, socket(active)))
    }

    @Test
    fun `local capacity cannot hide an already observed producer failure`() {
        val active = sessions.reserve(identity)
        val activeSocket = socket(active)
        assertTrue(sessions.attach(active, activeSocket))
        publicationState = null
        assertTrue(publication.checkOrdinary() == ServerAdmissionDecision.Denied.UNAVAILABLE)
        localCapacity = true
        nowNanos.set(Duration.ofSeconds(1).toNanos())
        sessions.sweep()
        verify(activeSocket).close(CloseStatus.POLICY_VIOLATION)
    }

    @Test
    fun `local capacity cannot reuse a proof after a higher verifying revision`() {
        val active = sessions.reserve(identity)
        val activeSocket = socket(active)
        assertTrue(sessions.attach(active, activeSocket))
        publicationState = ServerPublicationState.VERIFYING
        publicationRevision = 2
        assertTrue(publication.checkOrdinary() == ServerAdmissionDecision.Denied.NOT_PUBLIC)
        localCapacity = true
        nowNanos.set(Duration.ofSeconds(1).toNanos())
        sessions.sweep()
        verify(activeSocket).close(CloseStatus.POLICY_VIOLATION)
    }

    @Test
    fun `verifying publication closes a still-current battle without role bypass`() {
        val reservation = sessions.reserve(identity)
        val session = socket(reservation)
        assertTrue(sessions.attach(reservation, session))
        allowCurrent()
        publicationState = ServerPublicationState.VERIFYING
        publicationRevision = 2
        sessions.sweep()
        verify(session).close(CloseStatus.POLICY_VIOLATION)
        verify(tickets, never()).isCurrent(identity)
    }

    @Test
    fun `ping pong activity keeps a connection alive and missed pongs close it`() {
        val reservation = sessions.reserve(identity)
        val session = socket(reservation)
        assertTrue(sessions.attach(reservation, session))
        allowCurrent()
        nowNanos.set(Duration.ofSeconds(15).toNanos())
        sessions.sweep()
        verify(session).sendMessage(any(PingMessage::class.java))
        nowNanos.set(Duration.ofSeconds(59).toNanos())
        sessions.pong(session)
        nowNanos.set(Duration.ofSeconds(61).toNanos())
        sessions.sweep()
        verify(session, never()).close(any(CloseStatus::class.java))
        nowNanos.set(Duration.ofSeconds(120).toNanos())
        sessions.sweep()
        verify(session).close(CloseStatus.GOING_AWAY)
    }
}
