package opensamguk.gameapi.battle.realtime

import java.util.Base64
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.*

class BattleJoinTicketServiceTest {
    private val world = WorldId(1)
    private val now = Instant.parse("2026-09-27T00:02:00Z")
    private val participant = FrozenBattleParticipant(1, 42, 7, "ATTACKER", 3)
    private val ticket = FrozenBattleTicket(world, "battle-1", "{}", "a".repeat(64),
        "a".repeat(64), "a".repeat(64), "a".repeat(64), 17, 4, 2,
        now.minusSeconds(60), now.plusSeconds(300), listOf(participant))
    private fun head(epoch: Long = 1, leaseUntil: Instant = now.plusSeconds(30),
                     phase: BattleSessionPhase = BattleSessionPhase.RUNNING,
                     deadlineAt: Instant = ticket.deadlineAt) =
        BattleSessionHead(world, "battle-1", phase, epoch, 0, 0, 0,
            "actor", leaseUntil, ticket.joinDeadlineAt, deadlineAt)
    private fun service(store: FakeStore, at: Instant = now, secret: ByteArray = ByteArray(32) { 7 }) =
        BattleJoinTicketService(store, secret, Clock.fixed(at, ZoneOffset.UTC), "pep")

    @Test
    fun `authenticated participant receives a short ticket scoped to current epoch`() {
        val store = FakeStore(ticket, head())
        val signer = service(store)
        val token = signer.issue(world, "battle-1", 42, 7)
        val claims = signer.verify(token, world, "battle-1", 42)
        assertEquals(1, claims.participantId)
        assertEquals("pep", claims.serverId)
        assertEquals(7, claims.generalId)
        assertEquals("ATTACKER", claims.side)
        assertEquals(1L, claims.sessionEpoch)
        assertEquals(now.plusSeconds(60), claims.expiresAt)
    }

    @Test
    fun `different account and changed signature cannot borrow authority`() {
        val store = FakeStore(ticket, head())
        val signer = service(store)
        val token = signer.issue(world, "battle-1", 42, 7)
        assertFailsWith<SecurityException> { signer.verify(token, world, "battle-1", 43) }
        assertFailsWith<SecurityException> { signer.verify(token, WorldId(2), "battle-1", 42) }
        assertFailsWith<SecurityException> { signer.verify(token, world, "battle-2", 42) }
        assertFailsWith<SecurityException> { signer.verifyBearer(token, "other", world, "battle-1") }
        assertFailsWith<SecurityException> {
            service(store, secret = ByteArray(32) { 8 }).verifyBearer(token, "pep", world, "battle-1")
        }
        val parts = token.split('.')
        val changedPayload = Base64.getUrlDecoder().decode(parts[1]).also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }
        val forged = "${parts[0]}.${Base64.getUrlEncoder().withoutPadding().encodeToString(changedPayload)}.${parts[2]}"
        assertFailsWith<SecurityException> { signer.verify(forged, world, "battle-1", 42) }
        val changedFirst = if (parts[2].first() == 'A') 'B' else 'A'
        assertFailsWith<SecurityException> {
            signer.verify("${parts[0]}.${parts[1]}.$changedFirst${parts[2].drop(1)}", world, "battle-1", 42)
        }
        assertFailsWith<SecurityException> { signer.issue(world, "battle-1", 43, 7) }
        assertFailsWith<SecurityException> { signer.issue(world, "battle-1", 42, 8) }
    }

    @Test
    fun `epoch change and expiry invalidate a formerly valid ticket`() {
        val store = FakeStore(ticket, head())
        val token = service(store).issue(world, "battle-1", 42, 7)
        store.currentHead = head(epoch = 2)
        assertFailsWith<SecurityException> { service(store).verify(token, world, "battle-1", 42) }
        store.currentHead = head(epoch = 1, leaseUntil = now.plusSeconds(120))
        assertFailsWith<SecurityException> {
            service(store, now.plusSeconds(61)).verify(token, world, "battle-1", 42)
        }
    }

    @Test
    fun `database clock regression beyond allowed skew invalidates issued ticket`() {
        val store = FakeStore(ticket, head())
        val token = service(store).issue(world, "battle-1", 42, 7)
        assertFailsWith<SecurityException> {
            service(store, now.minusSeconds(6)).verify(token, world, "battle-1", 42)
        }
    }

    @Test
    fun `lease phase participant revision and general identity fence admission`() {
        val store = FakeStore(ticket, head())
        val token = service(store).issue(world, "battle-1", 42, 7)
        store.currentHead = head(leaseUntil = now.minusSeconds(1))
        assertFailsWith<SecurityException> { service(store).verify(token, world, "battle-1", 42) }
        assertFailsWith<SecurityException> { service(store).issue(world, "battle-1", 42, 7) }
        store.currentHead = head(phase = BattleSessionPhase.RESOLVING)
        assertFailsWith<SecurityException> { service(store).verify(token, world, "battle-1", 42) }
        assertFailsWith<SecurityException> { service(store).issue(world, "battle-1", 42, 7) }
        store.currentHead = head()
        store.currentTicket = ticket.copy(participants = listOf(participant.copy(authorityRevision = 4)))
        assertFailsWith<SecurityException> { service(store).verify(token, world, "battle-1", 42) }
        store.currentTicket = ticket.copy(participants = listOf(participant.copy(generalId = 8)))
        assertFailsWith<SecurityException> { service(store).verify(token, world, "battle-1", 42) }
    }

    @Test
    fun `ticket expiry is cut to battle deadline`() {
        val shortDeadline = now.plusSeconds(25)
        val shortTicket = ticket.copy(deadlineAt = shortDeadline)
        val store = FakeStore(shortTicket, head(leaseUntil = now.plusSeconds(120), deadlineAt = shortDeadline))
        val signer = service(store)
        val token = signer.issue(world, "battle-1", 42, 7)
        assertEquals(shortDeadline, signer.verify(token, world, "battle-1", 42).expiresAt)
        assertFailsWith<SecurityException> {
            service(store, shortDeadline).verify(token, world, "battle-1", 42)
        }
    }

    @Test
    fun `connected identity closes on epoch lease owner and authority changes while short ticket expiry only gates entry`() {
        val store = FakeStore(ticket, head(leaseUntil = now.plusSeconds(120)))
        val signer = service(store)
        val identity = signer.verifyBearer(signer.issue(world, "battle-1", 42, 7), "pep", world, "battle-1")
        assertTrue(signer.isCurrent(identity))
        assertTrue(service(store, now.plusSeconds(61)).isCurrent(identity))
        store.currentHead = head(epoch = 2, leaseUntil = now.plusSeconds(120))
        assertFalse(signer.isCurrent(identity))
        store.currentHead = head(leaseUntil = now.minusSeconds(1))
        assertFalse(signer.isCurrent(identity))
        store.currentHead = head().copy(leaseOwner = null)
        assertFalse(signer.isCurrent(identity))
        store.currentHead = head()
        store.currentTicket = ticket.copy(participants = listOf(participant.copy(authorityRevision = 4)))
        assertFalse(signer.isCurrent(identity))
    }

    private class FakeStore(var currentTicket: FrozenBattleTicket, var currentHead: BattleSessionHead) : BattleSessionStore {
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
