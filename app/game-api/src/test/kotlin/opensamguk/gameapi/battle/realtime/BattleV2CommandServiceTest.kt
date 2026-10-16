package opensamguk.gameapi.battle.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.security.*
import opensamguk.infra.battle.realtime.*
import opensamguk.logic.battle.realtime.*
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession

class BattleV2CommandServiceTest {
    private val mapper = ObjectMapper()
    private val codec = BattleV2WireCodec(mapper)
    private val world = WorldId(1)
    private val identity = BattleJoinIdentity("pep", world, "battle", 42, 1, 7,
        "ATTACKER", 2, 3, Instant.now().plusSeconds(60))
    private val own = TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "701")
    private val placement = TacticalV2Deployment(0, "c".repeat(64),
        listOf(TacticalV2UnitSource(own, BattleSide.ATTACKER, 7, 7, 100, 0)),
        mapOf(own to TacticalV2Cell(0, 0)),
        mapOf(BattleSide.ATTACKER to setOf(TacticalV2Cell(0, 0), TacticalV2Cell(0, 1)),
            BattleSide.DEFENDER to emptySet()))
    private val store = mock(BattleSessionStore::class.java)
    private val frozen = mock(BattleV2FrozenPlacementCodec::class.java)
    private val service = BattleV2CommandService(store, frozen, codec)
    private val move = BattleV2DeploymentMoveWire(2, "DEPLOYMENT_MOVE", "id", own,
        TacticalV2Cell(0, 1), 2, 3, 0)
    private val ticket = FrozenBattleTicket(world, "battle", "{}", "a".repeat(64), "b".repeat(64),
        "c".repeat(64), "d".repeat(64), 1, 0, 0, Instant.now().plusSeconds(120),
        Instant.now().plusSeconds(300), listOf(FrozenBattleParticipant(1, 42, 7, "ATTACKER", 3)))

    private val placeholder = BattleCommandRecord(world, "battle", 1, "placeholder", "a".repeat(64),
        2, 3, 0, "ATTACKER", "{}")
    private fun anyRecord(): BattleCommandRecord = any(BattleCommandRecord::class.java) ?: placeholder

    private fun arrange() {
        `when`(store.ticket(world, "battle")).thenReturn(ticket)
        `when`(frozen.initialDeployment(ticket)).thenReturn(placement)
    }

    @Test
    fun `service admits exact intent and durable deployment without a v1 slot`() {
        arrange()
        `when`(store.admit(anyRecord())).thenReturn(CommandAdmission.Receipt(
            BattleCommandReceipt("id", BattleCommandVerdict.ACCEPTED, null, 0, null, null,
                3, deploymentRevisionAfter = 1)))
        val ack = mapper.readTree(service.move(identity, move))
        assertEquals("1", ack["receipt"]["deploymentRevisionAfter"].textValue())
        val record = ArgumentCaptor.forClass(BattleCommandRecord::class.java)
        verify(store).admit(record.capture() ?: placeholder)
        assertEquals(placement, record.value.deploymentMove?.initial)
        assertEquals(own, record.value.deploymentMove?.sourceKey)
        assertFalse(record.value.intentJson.contains("slot"))
        assertNotEquals(codec.intent(move), codec.intent(move.copy(expectedDeploymentRevision = 1)))
    }

    @Test
    fun `service maps conflict and rejects a mismatching authenticated participant before write`() {
        arrange()
        `when`(store.admit(anyRecord())).thenReturn(CommandAdmission.IdempotencyConflict)
        val ack = mapper.readTree(service.move(identity, move))
        assertEquals("IDEMPOTENCY_CONFLICT", ack["receipt"]["reasonCode"].textValue())
        clearInvocations(store)
        assertFails { service.move(identity.copy(generalId = 8), move) }
        verify(store, never()).admit(anyRecord())
    }

    private fun policy() = ServerAdmissionPolicy(ServerAdmissionSource {
        ServerAdmissionRead.Known(ServerAdmissionSnapshot("pep", ServerPublicationState.PUBLIC, 1),
            System.nanoTime(), Duration.ofSeconds(2).toNanos())
    })

    @Test
    fun `socket command is fenced by current account ownership and latest reservation`() {
        val tickets = mock(BattleJoinTicketService::class.java)
        val generals = mock(GeneralResolver::class.java)
        `when`(tickets.isCurrent(identity)).thenReturn(true)
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        val sessions = BattleWebSocketSessions(tickets, generals, policy())
        val reservation = sessions.reserve(identity)
        val socket = mock(WebSocketSession::class.java)
        `when`(socket.isOpen).thenReturn(true)
        `when`(socket.attributes).thenReturn(mutableMapOf<String, Any>(
            BattleWebSocketSessions.RESERVATION_ATTRIBUTE to reservation))
        assertTrue(sessions.attach(reservation, socket))
        var writes = 0
        val handler = BattleWebSocketHandler(sessions, { "snapshot" }, { _, _ -> writes++; "ack" })
        handler.handleMessage(socket, TextMessage("move"))
        assertEquals(1, writes)
        sessions.reserve(identity)
        handler.handleMessage(socket, TextMessage("move"))
        assertEquals(1, writes)
        verify(socket, atLeastOnce()).close(CloseStatus.GOING_AWAY)
        `when`(generals.resolveGeneralId(42L)).thenReturn(8)
        handler.handleMessage(socket, TextMessage("move"))
        assertEquals(1, writes)
        verify(socket, atLeastOnce()).close(CloseStatus.POLICY_VIOLATION)
    }

    @Test
    fun `malformed text and unsupported COMMAND close without invoking persistence`() {
        val tickets = mock(BattleJoinTicketService::class.java)
        val generals = mock(GeneralResolver::class.java)
        `when`(tickets.isCurrent(identity)).thenReturn(true)
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        for (text in listOf("{}", "{\"schemaVersion\":1}", "{\"schemaVersion\":2,\"t\":\"COMMAND\"}")) {
            val sessions = BattleWebSocketSessions(tickets, generals, policy())
            val reservation = sessions.reserve(identity)
            val socket = mock(WebSocketSession::class.java)
            `when`(socket.isOpen).thenReturn(true)
            `when`(socket.attributes).thenReturn(mutableMapOf<String, Any>(
                BattleWebSocketSessions.RESERVATION_ATTRIBUTE to reservation))
            assertTrue(sessions.attach(reservation, socket))
            BattleWebSocketHandler(sessions, { "snapshot" }, { _, frame ->
                codec.deploymentMove(frame)
                error("must not write")
            }).handleMessage(socket, TextMessage(text))
            verify(socket, atLeastOnce()).close(CloseStatus.POLICY_VIOLATION)
            verify(socket, never()).sendMessage(any(TextMessage::class.java))
        }
    }
}
