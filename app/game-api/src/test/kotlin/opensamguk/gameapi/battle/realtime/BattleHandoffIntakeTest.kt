package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.FrozenBattleTicket

class BattleHandoffIntakeTest {
    private val world = WorldId(1)
    private val opening = Instant.parse("2026-09-30T12:01:00Z")
    private val ending = Instant.parse("2026-09-30T12:06:00Z")

    @Test
    fun `committed row opens once and a repeat uses the same ticket identity`() {
        val row = handoff(payload())
        val opened = mutableListOf<FrozenBattleTicket>()
        val rejected = mutableListOf<String>()
        val intake = BattleHandoffIntake(reader(row), { _, reason -> rejected += reason }) { ticket ->
            opened += ticket
            opened.size == 1
        }
        assertEquals(BattleHandoffIntakeResult(1, 1, 0, 0), intake.scan(world))
        assertEquals(BattleHandoffIntakeResult(1, 0, 1, 0), intake.scan(world))
        assertEquals(2, opened.size)
        assertEquals(opened[0], opened[1])
        assertEquals(emptyList(), rejected)
    }

    @Test
    fun `altered bytes and duplicate keys cannot create a session`() {
        val invalidHash = handoff(payload()).copy(payloadSha256 = "0".repeat(64))
        val duplicateKey = handoff(payload().replace("\"causeEventId\":\"cause-1\"",
            "\"causeEventId\":\"cause-1\",\"causeEventId\":\"cause-1\""))
        for ((row, reason) in listOf(invalidHash to "HASH_MISMATCH", duplicateKey to "INVALID_SCHEMA")) {
            var opened = 0
            val rejected = mutableListOf<String>()
            val intake = BattleHandoffIntake(reader(row), { _, code -> rejected += code }) {
                opened++
                true
            }
            assertEquals(BattleHandoffIntakeResult(1, 0, 0, 1), intake.scan(world))
            assertEquals(0, opened)
            assertEquals(listOf(reason), rejected)
        }
    }

    @Test
    fun `store identity conflict is durable but a transient failure remains retryable`() {
        val row = handoff(payload())
        val rejected = mutableListOf<String>()
        val conflict = BattleHandoffIntake(reader(row), { _, reason -> rejected += reason }) {
            throw IllegalStateException("battle handoff identity conflict")
        }
        assertEquals(BattleHandoffIntakeResult(1, 0, 0, 1), conflict.scan(world))
        assertEquals(listOf("TICKET_CONFLICT"), rejected)
        val transient = BattleHandoffIntake(reader(row), { _, reason -> rejected += reason }) {
            throw IllegalStateException("database unavailable")
        }
        assertFailsWith<IllegalStateException> { transient.scan(world) }
        assertEquals(listOf("TICKET_CONFLICT"), rejected)
    }

    @Test
    fun `unavailable installed pins defer without opening or permanent rejection`() {
        val row = handoff(payload())
        val rejected = mutableListOf<String>()
        var opened = 0
        val intake = BattleHandoffIntake(reader(row), { _, reason -> rejected += reason },
            { false }) {
            opened++
            true
        }
        assertEquals(BattleHandoffIntakeResult(1, 0, 0, 0, 1), intake.scan(world))
        assertEquals(0, opened)
        assertEquals(emptyList(), rejected)
    }

    private fun reader(row: CommittedBattleHandoff) = object : CommittedBattleHandoffReader {
        override fun withoutTicket(worldId: WorldId, limit: Int): List<CommittedBattleHandoff> = listOf(row)
    }

    private fun handoff(body: String) = CommittedBattleHandoff(world, "battle-1", "cause-1", body,
        sha(body), "a".repeat(64), "b".repeat(64), "c".repeat(64), 17, 1, 1, opening, ending)

    private fun payload() = """{"schemaVersion":1,"worldId":1,"battleId":"battle-1","causeEventId":"cause-1","pacingMode":"REALTIME","participants":[{"participantId":1,"accountId":42,"generalId":7,"side":"ATTACKER","authorityRevision":3}]}"""

    private fun sha(body: String) = MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
