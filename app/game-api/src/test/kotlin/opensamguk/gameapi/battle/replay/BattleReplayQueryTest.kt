package opensamguk.gameapi.battle.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.replay.BattleReplayArchive

class BattleReplayQueryTest {
    private val fixture = BattleReplayFixture()
    private val query = BattleReplayQuery(fixture.frozen)

    @Test
    fun `real codec timeline and publisher archive verifies but missing asset pins never grant publication`() {
        val archive = fixture.archive()
        val first = query.verify(archive)
        val restarted = BattleReplayQuery(fixture.frozen).verify(archive)
        assertEquals(first, restarted)
        assertEquals(BattleReplayReason.PIN_UNAVAILABLE, first.reason)
        assertEquals("BLOCKED_WRITE_DEPENDENCY", first.publication)
        val verified = assertNotNull(first.verification)
        assertEquals(archive.results.single().record.replayHash, verified.replayHash)
        assertEquals(2, verified.inputEventCount) // terminal BATTLE_RESOLVED is not a replay input.
        assertEquals(archive.results.single().appliedAt, verified.storedAppliedAt)
    }

    @Test
    fun `session and outbox must both carry synthetic applied storage evidence`() {
        val archive = fixture.archive()
        listOf(BattleSessionPhase.RESULT_PENDING, BattleSessionPhase.RESULT_BLOCKED,
            BattleSessionPhase.QUARANTINED).forEach { phase ->
            assertReason(BattleReplayReason.NOT_APPLIED, archive.copy(head = archive.head.copy(phase = phase)))
        }
        assertReason(BattleReplayReason.NOT_APPLIED, archive.copy(results = emptyList()))
        assertReason(BattleReplayReason.NOT_APPLIED, archive.copy(results = archive.results + archive.results))
        assertReason(BattleReplayReason.NOT_APPLIED, archive.copy(results = listOf(
            archive.results.single().copy(status = "PENDING"))))
        assertReason(BattleReplayReason.NOT_APPLIED, archive.copy(results = listOf(
            archive.results.single().copy(appliedAt = null))))
    }

    @Test
    fun `tampered frozen bytes and mismatched rule or catalog pins are distinct from absent asset pins`() {
        val archive = fixture.archive()
        assertReason(BattleReplayReason.PIN_MISMATCH, archive.copy(ticket = archive.ticket.copy(payloadJson = "{}")))
        assertReason(BattleReplayReason.PIN_MISMATCH, archive.copy(ticket = archive.ticket.copy(ruleSha256 = "0".repeat(64))))
        assertReason(BattleReplayReason.PIN_MISMATCH, archive.copy(ticket = archive.ticket.copy(catalogSha256 = "0".repeat(64))))
        val wrongRows = archive.ticket.payloadJson.replace(fixture.sha("P".repeat(4096)), "0".repeat(64))
        assertReason(BattleReplayReason.PIN_MISMATCH, withTicketPayload(archive, wrongRows))
    }

    @Test
    fun `mixed world head and result cannot validate as a single archive`() {
        val archive = fixture.archive()
        assertReason(BattleReplayReason.PIN_MISMATCH, archive.copy(head = archive.head.copy(worldId = WorldId(2))))
        val stored = archive.results.single()
        assertReason(BattleReplayReason.RESULT_MISMATCH, archive.copy(results = listOf(
            stored.copy(record = stored.record.copy(worldId = WorldId(2))))))
    }

    @Test
    fun `future schema and malformed kind cannot fall back to v1`() {
        val archive = fixture.archive()
        assertReason(BattleReplayReason.UNSUPPORTED_SCHEMA, withTicketPayload(archive,
            archive.ticket.payloadJson.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2")))
        assertReason(BattleReplayReason.PIN_MISMATCH, withTicketPayload(archive,
            archive.ticket.payloadJson.replace("\"kind\":\"ENCOUNTER\"", "\"kind\":[]")))
    }

    @Test
    fun `input byte corruption gaps duplicate sequences and backwards epoch fail closed`() {
        val archive = fixture.archive()
        val inputs = archive.events
        assertReason(BattleReplayReason.INPUT_MISMATCH, archive.copy(events = inputs.toMutableList().apply {
            this[1] = this[1].copy(payloadJson = "{}")
        }))
        assertReason(BattleReplayReason.INPUT_MISMATCH, archive.copy(events = inputs.drop(1)))
        assertReason(BattleReplayReason.INPUT_MISMATCH, archive.copy(events = inputs.toMutableList().apply {
            this[1] = this[1].copy(eventSeq = 1)
        }))
        assertReason(BattleReplayReason.INPUT_MISMATCH, archive.copy(events = inputs.toMutableList().apply {
            this[0] = this[0].copy(sessionEpoch = 2)
        }))
    }

    @Test
    fun `automatic decisions altered with recomputed checksum still fail replay`() {
        val archive = fixture.archive()
        val changed = archive.events[1].payloadJson.replace("FORMATION", "CHARGE")
        assertReason(BattleReplayReason.INPUT_MISMATCH, archive.copy(events = archive.events.toMutableList().apply {
            this[1] = this[1].copy(payloadJson = changed, payloadSha256 = fixture.sha(changed))
        }))
    }

    @Test
    fun `semantically same input with new byte checksum still violates stored replay hash`() {
        val archive = fixture.archive()
        val changed = archive.events[0].payloadJson + " "
        assertReason(BattleReplayReason.REPLAY_HASH_MISMATCH, archive.copy(events = archive.events.toMutableList().apply {
            this[0] = this[0].copy(payloadJson = changed, payloadSha256 = fixture.sha(changed))
        }))
    }

    @Test
    fun `terminal event identity checksum and duplicate resolution are validated outside the timeline`() {
        val archive = fixture.archive()
        assertReason(BattleReplayReason.RESULT_MISMATCH, archive.copy(events = archive.events.toMutableList().apply {
            this[2] = this[2].copy(payloadJson = "{}")
        }))
        assertReason(BattleReplayReason.RESULT_MISMATCH, archive.copy(events = archive.events.toMutableList().apply {
            this[2] = this[2].copy(payloadSha256 = "0".repeat(64))
        }))
        assertReason(BattleReplayReason.RESULT_MISMATCH, archive.copy(events = archive.events.toMutableList().apply {
            this[0] = this[0].copy(type = "BATTLE_RESOLVED")
        }))
    }

    @Test
    fun `modified result state with recomputed result checksum is rejected semantically`() {
        val archive = fixture.archive()
        val changed = archive.results.single().record.resultJson.replace("\"tick\":1", "\"tick\":2")
        assertReason(BattleReplayReason.RESULT_MISMATCH, withResultPayload(archive, changed))
        val badResolution = archive.results.single().record.resultJson.replace("TIMEOUT_SCORE", "TACTICAL")
        assertReason(BattleReplayReason.RESULT_MISMATCH, withResultPayload(archive, badResolution))
    }

    @Test
    fun `stored replay hash mismatch is separate from result byte and semantic mismatch`() {
        val archive = fixture.archive()
        val stored = archive.results.single()
        assertReason(BattleReplayReason.REPLAY_HASH_MISMATCH, archive.copy(results = listOf(
            stored.copy(record = stored.record.copy(replayHash = "0".repeat(64))))))
        assertReason(BattleReplayReason.RESULT_MISMATCH, archive.copy(results = listOf(
            stored.copy(record = stored.record.copy(resultJson = stored.record.resultJson + " ")))))
    }

    @Test
    fun `checkpoint checksum and cursor must agree with full frozen input replay`() {
        val archive = fixture.archive()
        val checkpoint = assertNotNull(archive.checkpoint)
        assertReason(BattleReplayReason.INPUT_MISMATCH, archive.copy(checkpoint = checkpoint.copy(stateHash = "0".repeat(64))))
        assertReason(BattleReplayReason.INPUT_MISMATCH, archive.copy(checkpoint = checkpoint.copy(eventSeq = 1)))
        assertReason(BattleReplayReason.INPUT_MISMATCH, archive.copy(checkpoint = checkpoint.copy(worldId = WorldId(2))))
        assertEquals(BattleReplayReason.PIN_UNAVAILABLE, query.verify(archive.copy(checkpoint = null)).reason)
    }

    private fun assertReason(reason: BattleReplayReason, archive: BattleReplayArchive) {
        val view = query.verify(archive)
        assertEquals(reason, view.reason)
        assertEquals("BLOCKED_WRITE_DEPENDENCY", view.publication)
        assertNull(view.verification)
    }

    private fun withTicketPayload(archive: BattleReplayArchive, payload: String) = archive.copy(
        ticket = archive.ticket.copy(payloadJson = payload, payloadSha256 = fixture.sha(payload)))

    private fun withResultPayload(archive: BattleReplayArchive, payload: String): BattleReplayArchive {
        val stored = archive.results.single()
        val result = stored.record.copy(resultJson = payload, resultSha256 = fixture.sha(payload))
        return archive.copy(results = listOf(stored.copy(record = result)), events = archive.events.dropLast(1) +
            archive.events.last().copy(payloadJson = payload, payloadSha256 = fixture.sha(payload)))
    }
}
