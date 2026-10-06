package opensamguk.gameapi.battle.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import opensamguk.common.world.WorldId
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.security.ServerAdmissionPolicy
import opensamguk.gameapi.security.ServerAdmissionRead
import opensamguk.gameapi.security.ServerAdmissionSnapshot
import opensamguk.gameapi.security.ServerAdmissionSource
import opensamguk.gameapi.security.ServerPublicationState
import opensamguk.infra.battle.realtime.BattleSessionHead
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.infra.battle.realtime.FrozenBattleParticipant
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.TacticalV2Cell
import opensamguk.logic.battle.realtime.TacticalV2Deployment
import opensamguk.logic.battle.realtime.TacticalV2SourceKey
import opensamguk.logic.battle.realtime.TacticalV2SourceKind
import opensamguk.logic.battle.realtime.TacticalV2UnitSource
import opensamguk.logic.battle.realtime.WaryongBoardCatalogResource
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.any
import org.mockito.ArgumentCaptor
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession

class BattleV2JoiningSnapshotTest {
    private val catalog = WaryongBoardCatalogResource.load()
    private val board = catalog.boards.first { it.landEligible && it.battlefield.kind == "FIELD" }
    private val passable = (0..63).flatMap { row -> (0..63).map { col -> TacticalV2Cell(row, col) } }
        .filter { board.battlefield.at(it.row, it.col) in "PFMR" }.take(3)
    private val own = TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "701")
    private val foreignAlly = TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "702")
    private val enemy = TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "801")
    private val world = WorldId(1)
    private val joinAt = Instant.parse("2026-10-06T01:00:00Z")
    private val deadline = Instant.parse("2026-10-06T01:05:00Z")
    private val ruleSha = "b".repeat(64)
    private val worldTerrainSha = "c".repeat(64)
    private val boardInputSha = sha(board.battlefield.rows.joinToString("").toByteArray(Charsets.US_ASCII))
    private val allowed = mapOf(BattleSide.ATTACKER to setOf(passable[0], passable[1]),
        BattleSide.DEFENDER to setOf(passable[2]))
    private val placement = TacticalV2Deployment(board.battlefield.id, boardInputSha,
        listOf(TacticalV2UnitSource(own, BattleSide.ATTACKER, 7, 7, 100, 2),
            TacticalV2UnitSource(foreignAlly, BattleSide.ATTACKER, 9, 9, 85, 4),
            TacticalV2UnitSource(enemy, BattleSide.DEFENDER, 8, 8, 50, 3)).sortedBy { it.key },
        mapOf(own to passable[0], foreignAlly to passable[1], enemy to passable[2]), allowed,
        revision = 4)
    private val identity = BattleJoinIdentity("pep", world, "battle-v2", 42, 1, 7,
        "ATTACKER", 3, 11, deadline)
    private val participants = listOf(FrozenBattleParticipant(1, 42, 7, "ATTACKER", 11),
        FrozenBattleParticipant(2, 43, 9, "ATTACKER", 12),
        FrozenBattleParticipant(3, 44, 8, "DEFENDER", 13))
    private val ticket = ticket()
    private val store = mock(BattleSessionStore::class.java)
    private val tickets = mock(BattleJoinTicketService::class.java)
    private val generals = mock(GeneralResolver::class.java)
    private val mapper = ObjectMapper().registerModule(JavaTimeModule())
    private val publisher = BattleV2JoiningSnapshotPublisher(tickets, generals, store,
        BattleV2FrozenPlacementCodec(catalog, ruleSha) { allowed }, catalog, mapper)

    @Test
    fun `pinned joining state serializes one controlled source and exact board input hash`() {
        arrange()
        val actual = publisher.snapshot(identity)
        val expected = requireNotNull(javaClass.getResourceAsStream("/battle/v2-joining-snapshot.json"))
            .bufferedReader().use { it.readText() }
        assertEquals(mapper.readTree(expected), mapper.readTree(actual))
        assertFalse(actual.contains("702"))
        assertFalse(actual.contains("801"))
        assertFalse(actual.contains(worldTerrainSha))
        assertNull(mapper.readTree(actual).get("observedUnits"))
    }

    @Test
    fun `missing authority or participant never produces a frame`() {
        arrange()
        `when`(tickets.isCurrent(identity)).thenReturn(false)
        assertFailsWith<IllegalStateException> { publisher.snapshot(identity) }
        `when`(tickets.isCurrent(identity)).thenReturn(true)
        `when`(store.ticket(world, "battle-v2")).thenReturn(ticket.copy(
            participants = participants.filterNot { it.participantId == 1 }))
        assertFailsWith<SecurityException> { publisher.snapshot(identity) }
    }

    @Test
    fun `missing pin or post joining event fails closed`() {
        arrange()
        `when`(store.ticket(world, "battle-v2")).thenReturn(ticket.copy(payloadJson = "{}"))
        assertFailsWith<IllegalArgumentException> { publisher.snapshot(identity) }
        `when`(store.ticket(world, "battle-v2")).thenReturn(ticket)
        `when`(store.head(world, "battle-v2")).thenReturn(head().copy(latestEventSeq = 1))
        assertFailsWith<IllegalArgumentException> { publisher.snapshot(identity) }
        `when`(store.head(world, "battle-v2")).thenReturn(head().copy(phase = BattleSessionPhase.RUNNING))
        assertFailsWith<IllegalArgumentException> { publisher.snapshot(identity) }
    }

    @Test
    fun `socket join sends the real pinned snapshot fixture once`() {
        arrange()
        val sessions = BattleWebSocketSessions(tickets, generals, publicPolicy())
        val reservation = sessions.reserve(identity)
        val session = socket(reservation)
        BattleWebSocketHandler(sessions, publisher::snapshot).afterConnectionEstablished(session)
        val sent = ArgumentCaptor.forClass(TextMessage::class.java)
        verify(session).sendMessage(sent.capture())
        val expected = requireNotNull(javaClass.getResourceAsStream("/battle/v2-joining-snapshot.json"))
            .bufferedReader().use { it.readText() }
        assertEquals(mapper.readTree(expected), mapper.readTree(sent.value.payload))
        verify(session, never()).close(any(CloseStatus::class.java))
    }

    @Test
    fun `head change during socket projection closes without a frame`() {
        arrange()
        `when`(store.head(world, "battle-v2")).thenReturn(head(), head().copy(latestEventSeq = 1))
        val sessions = BattleWebSocketSessions(tickets, generals, publicPolicy())
        val reservation = sessions.reserve(identity)
        val session = socket(reservation)
        BattleWebSocketHandler(sessions, publisher::snapshot).afterConnectionEstablished(session)
        verify(session, never()).sendMessage(any(TextMessage::class.java))
        verify(session).close(CloseStatus.POLICY_VIOLATION)
    }

    @Test
    fun `publication changes after attach deny the first protected frame`() {
        arrange()
        var reads = 0
        val policy = ServerAdmissionPolicy(ServerAdmissionSource {
            reads++
            ServerAdmissionRead.Known(ServerAdmissionSnapshot("pep",
                if (reads == 1) ServerPublicationState.PUBLIC else ServerPublicationState.VERIFYING,
                reads.toLong()), System.nanoTime(), Duration.ofSeconds(2).toNanos())
        })
        val sessions = BattleWebSocketSessions(tickets, generals, policy)
        val reservation = sessions.reserve(identity)
        val session = socket(reservation)
        BattleWebSocketHandler(sessions, publisher::snapshot).afterConnectionEstablished(session)
        verify(session, never()).sendMessage(any(TextMessage::class.java))
        verify(session).close(CloseStatus.POLICY_VIOLATION)
    }

    @Test
    fun `missing pinned board source closes without a v1 fallback frame`() {
        val sessions = BattleWebSocketSessions(tickets, generals, publicPolicy())
        val reservation = sessions.reserve(identity)
        val session = socket(reservation)
        BattleWebSocketHandler(sessions).afterConnectionEstablished(session)
        verify(session, never()).sendMessage(any(TextMessage::class.java))
        verify(session).close(CloseStatus.POLICY_VIOLATION)
    }

    private fun arrange() {
        `when`(tickets.isCurrent(identity)).thenReturn(true)
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        `when`(store.ticket(world, "battle-v2")).thenReturn(ticket)
        `when`(store.head(world, "battle-v2")).thenReturn(head())
    }

    private fun socket(reservation: BattleWebSocketSessions.Reservation) =
        mock(WebSocketSession::class.java).also { session ->
            `when`(session.attributes).thenReturn(mutableMapOf<String, Any>(
                BattleWebSocketSessions.RESERVATION_ATTRIBUTE to reservation))
            `when`(session.isOpen).thenReturn(true)
        }

    private fun publicPolicy() = ServerAdmissionPolicy(ServerAdmissionSource {
        ServerAdmissionRead.Known(ServerAdmissionSnapshot("pep", ServerPublicationState.PUBLIC, 1),
            System.nanoTime(), Duration.ofSeconds(2).toNanos())
    })

    private fun head() = BattleSessionHead(world, "battle-v2", BattleSessionPhase.JOINING,
        3, 0, 0, 0, "actor", deadline, joinAt, deadline)

    private fun ticket(): FrozenBattleTicket {
        val pin = BattleV2PlacementPin.from(placement)
        val body = """{"schemaVersion":2,"worldId":1,"battleId":"battle-v2","kind":"ENCOUNTER","battlefieldId":${board.battlefield.id},"ruleSha256":"$ruleSha","catalogSha256":"${catalog.catalogSha256}","terrainSha256":"$worldTerrainSha","seed":17,"lockGeneration":4,"lockSetRevision":2,"joinDeadlineAt":"$joinAt","deadlineAt":"$deadline","tacticalInput":{"schemaVersion":2,"placement":{"schemaVersion":2,"boardId":${pin.boardId},"terrainSha256":"${pin.terrainSha256}","sourceCount":${pin.sourceCount},"deploymentBase64":"${pin.deploymentBase64}","deploymentSha256":"${pin.deploymentSha256}"}}}"""
        return FrozenBattleTicket(world, "battle-v2", body, sha(body.toByteArray()), ruleSha,
            catalog.catalogSha256, worldTerrainSha, 17, 4, 2, joinAt, deadline, participants)
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
