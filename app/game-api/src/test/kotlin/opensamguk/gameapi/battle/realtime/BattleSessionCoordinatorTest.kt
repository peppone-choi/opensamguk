package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.*
import opensamguk.logic.battle.realtime.*

class BattleSessionCoordinatorTest {
    private val worldId = WorldId(1)
    private val hash = "a".repeat(64)
    private val participant = FrozenBattleParticipant(1, 42, 7, "ATTACKER", 3)

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun ticket(payloadOverride: String? = null): FrozenBattleTicket {
        val payload = payloadOverride ?: """{"schemaVersion":1,"battleId":"battle-1","worldId":1,"kind":"ENCOUNTER","battlefieldId":192,"ruleSha256":"$hash","catalogSha256":"$hash","terrainSha256":"$hash","seed":17,"lockGeneration":4,"lockSetRevision":2,"joinDeadlineAt":"2026-09-27T00:01:00Z","deadlineAt":"2026-09-27T00:06:00Z","entityRevisions":{"general:7":1},"participants":[{"participantId":1,"accountId":42,"generalId":7,"side":"ATTACKER","authorityRevision":3}]}"""
        return FrozenBattleTicket(worldId, "battle-1", payload, sha(payload), hash, hash, hash,
            17, 4, 2, Instant.parse("2026-09-27T00:01:00Z"),
            Instant.parse("2026-09-27T00:06:00Z"), listOf(participant))
    }

    @Test
    fun `committed handoff hash and frozen authority must agree`() {
        val store = FakeStore()
        val coordinator = BattleSessionCoordinator(store)
        assertTrue(coordinator.open(ticket()))
        assertFailsWith<IllegalArgumentException> {
            coordinator.open(ticket().copy(payloadSha256 = "b".repeat(64)))
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator.open(ticket().copy(participants = listOf(participant.copy(accountId = 99))))
        }
        val aiOnlyPayload = ticket().payloadJson.replace("\"participants\":[{\"participantId\":1,\"accountId\":42,\"generalId\":7,\"side\":\"ATTACKER\",\"authorityRevision\":3}]", "\"participants\":[]")
        assertTrue(coordinator.open(ticket(aiOnlyPayload).copy(participants = emptyList())))
    }

    @Test
    fun `authenticated participant is checked before only tactical intent reaches store`() {
        val store = FakeStore().apply { currentTicket = ticket() }
        val coordinator = BattleSessionCoordinator(store)
        val input = BattleCommandInput(worldId, "battle-1", 42, 1, "cmd-1", 1, 3, 0,
            BattleSide.ATTACKER, FormationSlot.CENTER, BattleOrder.CHARGE, RallyPoint.ENEMY)
        assertFailsWith<SecurityException> { coordinator.submit(input.copy(accountId = 99)) }
        assertFailsWith<SecurityException> { coordinator.submit(input.copy(side = BattleSide.DEFENDER)) }
        assertEquals(null, store.command)
        coordinator.submit(input)
        val command = requireNotNull(store.command)
        assertEquals(sha(command.intentJson), command.intentSha256)
        assertTrue(command.intentJson.contains("\"order\":\"CHARGE\""))
        assertTrue(!command.intentJson.contains("position") && !command.intentJson.contains("damage"))
    }

    @Test
    fun `v1 submit requires canonical numeric schema one before admission`() {
        val original = ticket().payloadJson
        val marker = "\"schemaVersion\":1"
        val invalidPayloads = listOf(
            original.replace(marker, "\"schemaVersion\":2"),
            original.replace(marker, "\"schemaVersion\":\"1\""),
            original.replace(marker, "\"schemaVersion\":1e0"),
            original.replace(marker, "\"schemaVersion\":1.0"),
            original.replace("$marker,", ""),
            "{not json",
        )
        val input = BattleCommandInput(worldId, "battle-1", 42, 1, "cmd-1", 1, 3, 0,
            BattleSide.ATTACKER, FormationSlot.CENTER, BattleOrder.CHARGE, RallyPoint.ENEMY)

        invalidPayloads.forEachIndexed { index, payload ->
            val store = FakeStore().apply { currentTicket = ticket(payload) }
            assertFails("invalid schema variant $index must be rejected") {
                BattleSessionCoordinator(store).submit(input)
            }
            assertEquals(null, store.command)
        }
    }

    @Test
    fun `recovery rejects tampered committed event`() {
        val store = FakeStore().apply {
            currentTicket = ticket()
            eventLog = listOf(BattleEventRecord(1, 1, 0, 1, "COMMAND_ACCEPTED", "{}", "0".repeat(64)))
        }
        assertFailsWith<IllegalArgumentException> {
            BattleSessionCoordinator(store).recover(worldId, "battle-1")
        }
    }

    private class FakeStore : BattleSessionStore {
        var currentTicket: FrozenBattleTicket? = null
        var command: BattleCommandRecord? = null
        var eventLog = emptyList<BattleEventRecord>()
        override fun create(ticket: FrozenBattleTicket): Boolean { currentTicket = ticket; return true }
        override fun ticket(worldId: WorldId, battleId: String) = currentTicket
        override fun head(worldId: WorldId, battleId: String): BattleSessionHead? = null
        override fun claimEpoch(worldId: WorldId, battleId: String, owner: String, leaseMillis: Long): BattleSessionHead? = null
        override fun renewLease(worldId: WorldId, battleId: String, owner: String,
                                sessionEpoch: Long, leaseMillis: Long) = false
        override fun startRun(worldId: WorldId, battleId: String, owner: String, sessionEpoch: Long) = false
        override fun admit(command: BattleCommandRecord): CommandAdmission {
            this.command = command
            return CommandAdmission.Receipt(BattleCommandReceipt(command.clientCommandId,
                BattleCommandVerdict.ACCEPTED, null, command.issuedTick, command.issuedTick + 1, 1,
                command.expectedAuthorityRevision))
        }
        override fun appendTransition(transition: BattleTransition): Long? = null
        override fun advanceTick(worldId: WorldId, battleId: String, owner: String,
                                 sessionEpoch: Long, expectedTick: Int, expectedEventSeq: Long) = false
        override fun checkpoint(checkpoint: BattleCheckpoint) = false
        override fun eventsAfter(worldId: WorldId, battleId: String, eventSeq: Long) = eventLog
        override fun latestCheckpoint(worldId: WorldId, battleId: String): BattleCheckpoint? = null
        override fun publishResult(result: BattleResultRecord) = false
        override fun pendingResults(worldId: WorldId, limit: Int) = emptyList<BattleResultRecord>()
        override fun markApplied(worldId: WorldId, battleId: String, resultRevision: Int) = false
        override fun markBlocked(worldId: WorldId, battleId: String, resultRevision: Int, reason: String) = false
    }
}
