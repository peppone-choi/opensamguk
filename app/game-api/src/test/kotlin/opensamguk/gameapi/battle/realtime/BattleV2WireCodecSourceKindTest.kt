package opensamguk.gameapi.battle.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.security.ServerAdmissionPolicy
import opensamguk.gameapi.security.ServerAdmissionRead
import opensamguk.gameapi.security.ServerAdmissionSnapshot
import opensamguk.gameapi.security.ServerAdmissionSource
import opensamguk.gameapi.security.ServerPublicationState
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.logic.battle.realtime.TacticalV2Cell
import opensamguk.logic.battle.realtime.TacticalV2SourceKey
import opensamguk.logic.battle.realtime.TacticalV2SourceKind
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession

class BattleV2WireCodecSourceKindTest {
    private val codec = BattleV2WireCodec(ObjectMapper())
    private val unsupportedKinds = listOf("UNKNOWN", "PRODUCED", "REINFORCEMENT")

    private fun move(sourceKey: String) = """{"schemaVersion":2,"t":"DEPLOYMENT_MOVE","clientCommandId":"move-1","sourceKey":$sourceKey,"targetCell":{"row":1,"col":2},"expectedEpoch":"2","expectedAuthorityRevision":"3","expectedDeploymentRevision":"0"}"""
    private fun key(kind: String) = """{"kind":"$kind","sourceId":701}"""
    private fun command(kind: String) = """{"schemaVersion":2,"t":"COMMAND","clientCommandId":"command-1","expectedEpoch":"2","expectedAuthorityRevision":"3","issuedTick":10,"scope":{"sourceKeys":[${key(kind)}]},"intentType":"ATTACK","intentPayload":{"rally":"CENTER"}}"""

    @Test
    fun `legacy move parsing and canonical intent retain fixed bytes for both source kinds`() {
        val cases = listOf(
            Triple(key("RETINUE"), TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "701"),
                """{"schemaVersion":2,"t":"DEPLOYMENT_MOVE","clientCommandId":"move-1","sourceKey":{"kind":"RETINUE","sourceId":"701","cityId":null},"targetCell":{"row":1,"col":2},"expectedEpoch":"2","expectedAuthorityRevision":"3","expectedDeploymentRevision":"0"}"""),
            Triple("""{"kind":"CITY_GARRISON_BUGOK","sourceId":17,"cityId":41}""",
                TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "17", 41),
                """{"schemaVersion":2,"t":"DEPLOYMENT_MOVE","clientCommandId":"move-1","sourceKey":{"kind":"CITY_GARRISON_BUGOK","sourceId":"17","cityId":41},"targetCell":{"row":1,"col":2},"expectedEpoch":"2","expectedAuthorityRevision":"3","expectedDeploymentRevision":"0"}"""),
        )
        for ((source, expectedKey, intent) in cases) {
            val parsed = codec.deploymentMove(move(source))
            assertEquals(BattleV2DeploymentMoveWire(2, "DEPLOYMENT_MOVE", "move-1", expectedKey,
                TacticalV2Cell(1, 2), 2, 3, 0), parsed)
            assertContentEquals(intent.toByteArray(Charsets.UTF_8), codec.intent(parsed).toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `new deployment source kinds fail with the exact unknown kind exception type`() {
        for (kind in unsupportedKinds) {
            val error = assertFailsWith<IllegalArgumentException>(kind) {
                codec.deploymentMove(move(key(kind)))
            }
            assertEquals(IllegalArgumentException::class, error::class)
            if (kind != "UNKNOWN") assertEquals("unsupported deployment source kind", error.message)
        }
    }

    @Test
    fun `COMMAND explicit scopes reject new kinds just like an unknown source kind`() {
        for (kind in unsupportedKinds) {
            val error = assertFailsWith<IllegalArgumentException>(kind) { codec.command(command(kind)) }
            assertEquals(IllegalArgumentException::class, error::class)
        }
    }

    @Test
    fun `socket rejection matches unknown kind without state reads writes or receipts`() {
        val identity = BattleJoinIdentity("pep", WorldId(1), "battle", 42, 1, 7,
            "ATTACKER", 2, 3, Instant.now().plusSeconds(60))
        val tickets = mock(BattleJoinTicketService::class.java)
        val generals = mock(GeneralResolver::class.java)
        `when`(tickets.isCurrent(identity)).thenReturn(true)
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        val store = mock(BattleSessionStore::class.java)
        val frozen = mock(BattleV2FrozenPlacementCodec::class.java)
        val service = BattleV2CommandService(store, frozen, codec)
        val policy = ServerAdmissionPolicy(ServerAdmissionSource {
            ServerAdmissionRead.Known(ServerAdmissionSnapshot("pep", ServerPublicationState.PUBLIC, 1),
                System.nanoTime(), Duration.ofSeconds(2).toNanos())
        })
        for (kind in unsupportedKinds) {
            for (frame in listOf(move(key(kind)), command(kind))) {
                val sessions = BattleWebSocketSessions(tickets, generals, policy)
                val reservation = sessions.reserve(identity)
                val socket = mock(WebSocketSession::class.java)
                `when`(socket.isOpen).thenReturn(true)
                `when`(socket.attributes).thenReturn(mutableMapOf<String, Any>(
                    BattleWebSocketSessions.RESERVATION_ATTRIBUTE to reservation))
                assertTrue(sessions.attach(reservation, socket))
                BattleWebSocketHandler(sessions, { "snapshot" }, service::receive)
                    .handleMessage(socket, TextMessage(frame))
                verify(socket, atLeastOnce()).close(CloseStatus.POLICY_VIOLATION)
                verify(socket, never()).sendMessage(any(TextMessage::class.java))
            }
        }
        verifyNoInteractions(store, frozen)
    }

    @Test
    fun `legacy source ordering and persisted ordinals stay fixed after appended kinds`() {
        assertEquals(listOf(0, 1, 2, 3), listOf(TacticalV2SourceKind.RETINUE,
            TacticalV2SourceKind.CITY_GARRISON_BUGOK, TacticalV2SourceKind.PRODUCED,
            TacticalV2SourceKind.REINFORCEMENT).map { it.ordinal })
        val keys = listOf(TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "2"),
            TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "17", 41),
            TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "10"),
            TacticalV2SourceKey(TacticalV2SourceKind.CITY_GARRISON_BUGOK, "3", 1))
        assertEquals(listOf(keys[2], keys[0], keys[3], keys[1]), keys.sorted())
    }
}
