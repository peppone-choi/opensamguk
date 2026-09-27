package opensamguk.gameapi.battle.realtime

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.*

class BattleJoinTicketServiceTest {
    private val world = WorldId(1)
    private val now = Instant.parse("2026-09-27T00:02:00Z")
    private val participant = FrozenBattleParticipant(1, 42, 7, "ATTACKER", 3)
    private val ticket = FrozenBattleTicket(world, "battle-1", "{}", "a".repeat(64),
        "a".repeat(64), "a".repeat(64), "a".repeat(64), 17, 4, 2,
        now.minusSeconds(60), now.plusSeconds(300), listOf(participant))
    private fun head(epoch: Long = 1, leaseUntil: Instant = now.plusSeconds(30)) =
        BattleSessionHead(world, "battle-1", BattleSessionPhase.RUNNING, epoch, 0, 0, 0,
            "actor", leaseUntil, ticket.joinDeadlineAt, ticket.deadlineAt)
    private fun service(store: FakeStore, at: Instant = now, secret: ByteArray = ByteArray(32) { 7 }) =
        BattleJoinTicketService(store, secret, Clock.fixed(at, ZoneOffset.UTC))

    @Test
    fun `authenticated participant receives a short ticket scoped to current epoch`() {
        val store = FakeStore(ticket, head())
        val signer = service(store)
        val token = signer.issue(world, "battle-1", 42)
        val claims = signer.verify(token, world, "battle-1", 42)
        assertEquals(1, claims.participantId)
        assertEquals("ATTACKER", claims.side)
        assertEquals(1L, claims.sessionEpoch)
        assertEquals(now.plusSeconds(60), claims.expiresAt)
    }

    @Test
    fun `different account and changed signature cannot borrow authority`() {
        val store = FakeStore(ticket, head())
        val signer = service(store)
        val token = signer.issue(world, "battle-1", 42)
        assertFailsWith<SecurityException> { signer.verify(token, world, "battle-1", 43) }
        assertFailsWith<SecurityException> { signer.verify(token, WorldId(2), "battle-1", 42) }
        assertFailsWith<SecurityException> { signer.verify(token.dropLast(1) + "A", world, "battle-1", 42) }
        assertFailsWith<SecurityException> { signer.issue(world, "battle-1", 43) }
    }

    @Test
    fun `epoch change and expiry invalidate a formerly valid ticket`() {
        val store = FakeStore(ticket, head())
        val token = service(store).issue(world, "battle-1", 42)
        store.currentHead = head(epoch = 2)
        assertFailsWith<SecurityException> { service(store).verify(token, world, "battle-1", 42) }
        store.currentHead = head(epoch = 1, leaseUntil = now.plusSeconds(120))
        assertFailsWith<SecurityException> {
            service(store, now.plusSeconds(61)).verify(token, world, "battle-1", 42)
        }
    }

    private class FakeStore(val currentTicket: FrozenBattleTicket, var currentHead: BattleSessionHead) : BattleSessionStore {
        override fun ticket(worldId: WorldId, battleId: String) = currentTicket
        override fun head(worldId: WorldId, battleId: String) = currentHead
        override fun create(ticket: FrozenBattleTicket) = error("unused")
        override fun claimEpoch(worldId: WorldId, battleId: String, owner: String, leaseMillis: Long): BattleSessionHead? = error("unused")
        override fun renewLease(worldId: WorldId, battleId: String, owner: String,
                                sessionEpoch: Long, leaseMillis: Long) = error("unused")
        override fun startRun(worldId: WorldId, battleId: String, owner: String, sessionEpoch: Long) = error("unused")
        override fun admit(command: BattleCommandRecord): CommandAdmission = error("unused")
        override fun appendTransition(transition: BattleTransition): Long? = error("unused")
        override fun advanceTick(worldId: WorldId, battleId: String, owner: String,
                                 sessionEpoch: Long, expectedTick: Int, expectedEventSeq: Long) = error("unused")
        override fun checkpoint(checkpoint: BattleCheckpoint) = error("unused")
        override fun eventsAfter(worldId: WorldId, battleId: String, eventSeq: Long): List<BattleEventRecord> = error("unused")
        override fun latestCheckpoint(worldId: WorldId, battleId: String): BattleCheckpoint? = error("unused")
        override fun publishResult(result: BattleResultRecord) = error("unused")
        override fun pendingResults(worldId: WorldId, limit: Int): List<BattleResultRecord> = error("unused")
        override fun markApplied(worldId: WorldId, battleId: String, resultRevision: Int) = error("unused")
        override fun markBlocked(worldId: WorldId, battleId: String, resultRevision: Int, reason: String) = error("unused")
    }
}
