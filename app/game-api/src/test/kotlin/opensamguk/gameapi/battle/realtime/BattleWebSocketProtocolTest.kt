package opensamguk.gameapi.battle.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.infra.battle.realtime.BattleCommandReceipt
import opensamguk.infra.battle.realtime.BattleCommandRecord
import opensamguk.infra.battle.realtime.BattleCommandVerdict
import opensamguk.infra.battle.realtime.BattleSessionHead
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.infra.battle.realtime.CommandAdmission
import opensamguk.infra.battle.realtime.FrozenBattleParticipant
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import opensamguk.logic.battle.realtime.BattleDeployment
import opensamguk.logic.battle.realtime.BattleOrder
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.Battlefield
import opensamguk.logic.battle.realtime.GeneralStats
import opensamguk.logic.battle.realtime.RallyPoint
import opensamguk.logic.battle.realtime.Retinue
import opensamguk.logic.battle.realtime.TacticalBattle
import opensamguk.logic.battle.realtime.UnitKind
import org.mockito.Mockito.*

class BattleWebSocketProtocolTest {
    private val world = WorldId(1)
    private val expires = Instant.parse("2026-10-01T08:05:00Z")
    private val identity = BattleJoinIdentity("pep", world, "battle-1", 42, 1, 7,
        "ATTACKER", 1, 3, expires)
    private val payload = "{}"
    private val ticket = FrozenBattleTicket(world, "battle-1", payload, sha(payload),
        "a".repeat(64), "b".repeat(64), "c".repeat(64), 17, 1, 1,
        expires.minusSeconds(60), expires, listOf(FrozenBattleParticipant(1, 42, 7, "ATTACKER", 3)))
    private val store = mock(BattleSessionStore::class.java)
    private val frozen = mock(BattleFrozenInputCodec::class.java)
    private val tickets = mock(BattleJoinTicketService::class.java)
    private val generals = mock(GeneralResolver::class.java)
    private val mapper = ObjectMapper().registerModule(JavaTimeModule())
    private val admitted = mutableListOf<BattleCommandRecord>()
    private val protocol = BattleWebSocketProtocol(tickets, generals, store, frozen,
        BattleSessionCoordinator(store), mapper)

    private fun arrange() {
        `when`(tickets.isCurrent(identity)).thenReturn(true)
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        `when`(store.ticket(world, "battle-1")).thenReturn(ticket)
        `when`(store.head(world, "battle-1")).thenReturn(BattleSessionHead(world, "battle-1",
            BattleSessionPhase.RUNNING, 1, 0, 0, 0, "actor", expires,
            ticket.joinDeadlineAt, ticket.deadlineAt))
        `when`(store.eventsAfter(world, "battle-1", 0)).thenReturn(emptyList())
        val field = Battlefield(1, "FIELD", List(64) { "P".repeat(64) })
        fun retinue(id: Int, generalId: Int) = Retinue(id,
            GeneralStats(generalId, 50, 50, 50, 50, 50), 100,
            UnitKind.INFANTRY, 50, 50, 0, 50, true)
        `when`(frozen.initialState(ticket)).thenReturn(TacticalBattle.start(17, field,
            BattleDeployment.default(BattleSide.ATTACKER, 7, listOf(retinue(701, 7))),
            BattleDeployment.default(BattleSide.DEFENDER, 8, listOf(retinue(801, 8))),
            humanSides = setOf(BattleSide.ATTACKER)))
        doAnswer { call ->
            val command = call.getArgument<BattleCommandRecord>(0)
            admitted += command
            val reason = command.preflightReasonCode
            CommandAdmission.Receipt(BattleCommandReceipt(command.clientCommandId,
                if (reason == null) BattleCommandVerdict.ACCEPTED else BattleCommandVerdict.REJECTED,
                reason, 0, if (reason == null) 1 else null, if (reason == null) 1 else null, 3))
        }.`when`(store).admit(anyRecord())
    }

    @Test
    fun `snapshot hides enemy and command matrix maps only owned retinue to its current slot`() {
        arrange()
        val snapshot = mapper.readTree(protocol.snapshot(identity))
        assertEquals("SNAPSHOT", snapshot["t"].asText())
        assertEquals(1, snapshot["units"].size())
        assertEquals(701, snapshot["units"][0]["retinueId"].asInt())
        assertEquals("CENTER", snapshot["units"][0]["slot"].asText())
        assertNull(snapshot.get("visibleEnemy"))
        assertNull(snapshot["units"][0].get("controller"))

        for (order in BattleOrder.entries) for (rally in RallyPoint.entries) {
            val id = "${order.name}-${rally.name}"
            val ack = mapper.readTree(protocol.command(identity, command(id, 701, order, rally)))
            assertEquals("ACCEPTED", ack["result"].asText())
            assertEquals(0, ack["acceptedTick"].asInt())
        }
        val denied = mapper.readTree(protocol.command(identity,
            command("foreign", 801, BattleOrder.CHARGE, RallyPoint.HOME)))
        assertEquals("UNAUTHORIZED", denied["reasonCode"].asText())
        assertEquals(19, admitted.size)
        assertTrue(admitted.take(18).all { it.preflightReasonCode == null &&
            it.intentJson.contains("\"slot\":\"CENTER\"") && it.mappedAtTick == 0 &&
            it.mappedAtEventSeq == 0L })
        assertEquals("UNAUTHORIZED", admitted.last().preflightReasonCode)
        assertTrue(admitted.last().intentJson.contains("\"slot\":null"))
    }

    @Test
    fun `idempotency conflict reports verified authority revision rather than request value`() {
        arrange()
        doReturn(CommandAdmission.IdempotencyConflict).`when`(store)
            .admit(anyRecord())
        val request = command("same-id", 701, BattleOrder.DEFEND, RallyPoint.CENTER)
            .replace("\"expectedAuthorityRevision\":3", "\"expectedAuthorityRevision\":999")
        val ack = mapper.readTree(protocol.command(identity, request))
        assertEquals("IDEMPOTENCY_CONFLICT", ack["reasonCode"].asText())
        assertEquals(3, ack["currentAuthorityRevision"].asInt())
    }

    private fun command(id: String, retinueId: Int, order: BattleOrder, rally: RallyPoint) =
        """{"schemaVersion":1,"t":"COMMAND","clientCommandId":"$id","expectedEpoch":1,"expectedAuthorityRevision":3,"issuedTick":0,"scope":{"retinueId":$retinueId},"intentType":"${order.name}","intentPayload":{"rally":"${rally.name}"}}"""

    private fun anyRecord(): BattleCommandRecord {
        any(BattleCommandRecord::class.java)
        return BattleCommandRecord(world, "battle-1", 1, "matcher", "a".repeat(64),
            1, 1, 0, "ATTACKER", "{}")
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
