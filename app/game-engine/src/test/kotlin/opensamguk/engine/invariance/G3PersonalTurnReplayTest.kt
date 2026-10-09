package opensamguk.engine.invariance

import opensamguk.engine.invariance.G3PersonalTurnReplayFixture.Probe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class G3PersonalTurnReplayTest {
    @Test fun `G3 same start and recorded input reproduce every complete personal turn of 36 phases`() {
        val fixture = G3PersonalTurnReplayFixture()
        val probe = System.getenv("OPENSAMGUK_G3_REPLAY_PROBE")?.let(Probe::valueOf) ?: Probe.NONE
        val first = fixture.replay("first")
        val second = fixture.replay("second-${probe.name}", probe)
        fixture.assertEquivalent(first, second)
        assertEquals(72, first.applied)
        assertEquals(36, first.frames.map { it.phase }.distinct().size)
        assertEquals(fixture.inputs.map { it.generalId }, first.frames.map { it.generalId })
        assertEquals(fixture.inputs.map { it.turnTime }, first.frames.map { it.turnTime })
        assertEquals(first.initialLink, first.frames.first().previousHash)
        first.frames.zipWithNext().forEach { (before, after) -> assertEquals(before.linkHash, after.previousHash) }
        assertTrue(first.frames.all { it.beforeStateHash != it.afterStateHash })
    }

    @Test fun `G3 hidden decision state divergence fails the same oracle even when final state converges`() {
        val fixture = G3PersonalTurnReplayFixture()
        val first = fixture.replay("hidden-control")
        val second = fixture.replay("hidden-mutated", Probe.HIDDEN_STATE)
        assertEquals(first.initialStateHash, second.initialStateHash)
        assertEquals(first.inputLogHash, second.inputLogHash)
        assertEquals(first.finalStateHash, second.finalStateHash, "temporary decision drift is restored")
        assertEquals(first.finalLogHash, second.finalLogHash)
        assertEquals(first.frames.take(G3PersonalTurnReplayFixture.MUTATION_TURN),
            second.frames.take(G3PersonalTurnReplayFixture.MUTATION_TURN))
        assertNotEquals(first.frames.last().linkHash, second.frames.last().linkHash)
        val failure = assertFailsWith<AssertionError> { fixture.assertEquivalent(first, second) }
        assertTrue(failure.message.orEmpty().contains("G3 personal turn 8"))
    }

    @Test fun `G3 tampered delivered input fails the same oracle with an unchanged recorded input log`() {
        val fixture = G3PersonalTurnReplayFixture()
        val first = fixture.replay("input-control")
        val second = fixture.replay("input-mutated", Probe.INPUT_TAMPER)
        assertEquals(first.initialStateHash, second.initialStateHash)
        assertEquals(first.inputLogHash, second.inputLogHash)
        assertEquals(first.consumed, second.consumed)
        assertEquals(first.frames.take(G3PersonalTurnReplayFixture.MUTATION_TURN),
            second.frames.take(G3PersonalTurnReplayFixture.MUTATION_TURN))
        assertNotEquals(first.frames[8].inputHash, second.frames[8].inputHash)
        assertNotEquals(first.finalStateHash, second.finalStateHash)
        val failure = assertFailsWith<AssertionError> { fixture.assertEquivalent(first, second) }
        assertTrue(failure.message.orEmpty().contains("G3 personal turn 8"))
    }

    @Test fun `G3 canonical hash retains keys types Id Time Hash values and ordered logs`() {
        val fixture = G3PersonalTurnReplayFixture()
        val values = linkedMapOf<String, Any?>("actorId" to 1, "turnTime" to "0200-01-01T00:00:00Z",
            "inputHash" to "a", "reservationRevision" to "r1", "worldVersion" to 7,
            "logs" to listOf("first", "second"), "nullValue" to null)
        assertEquals(fixture.hash(values), fixture.hash(values.entries.reversed().associate { it.toPair() }))
        for (key in values.keys) assertNotEquals(fixture.hash(values), fixture.hash(values - key), "retain key $key")
        for (key in listOf("actorId", "turnTime", "inputHash", "reservationRevision", "worldVersion")) {
            assertNotEquals(fixture.hash(values), fixture.hash(values + (key to "changed")), "retain value $key")
        }
        assertNotEquals(fixture.hash(values), fixture.hash(values + ("actorId" to "1")))
        assertNotEquals(fixture.hash(values), fixture.hash(values + ("logs" to listOf("second", "first"))))
        assertNotEquals(fixture.hash(values), fixture.hash(values + ("otherId" to values.getValue("actorId")) - "actorId"))
    }
}
