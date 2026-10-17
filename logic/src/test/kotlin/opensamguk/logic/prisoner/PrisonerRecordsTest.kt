package opensamguk.logic.prisoner

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal val sourceA = PrisonerSource("battle", "corps:1", 7, 2, "spear")
internal val sourceB = PrisonerSource("battle", "county:4", 3, 2, "spear")
internal fun ref(id: Long) = SoldierRef("battle", id)
internal fun captured(): PrisonerState {
    var state = PrisonerRecords.captureSoldiers(PrisonerState(), "capture-a", sourceA, setOf(1, 2, 3), 10).state
    state = PrisonerRecords.captureSoldiers(state, "capture-b", sourceB, setOf(4, 5), 10).state
    return PrisonerRecords.captureGeneral(state, "capture-general", 20, 2, true, 10).state
}

class PrisonerRecordsTest {
    @Test fun `source ledger survives derived nation and troop grouping`() {
        val state = captured()
        val group = state.groups().single()
        assertEquals(5, group.count)
        assertEquals(listOf(3, 2), group.sources.sortedBy { it.source.sourceKey }.map { it.count })
        assertEquals(setOf(sourceA, sourceB), group.sources.map { it.source }.toSet())
        assertEquals((1L..5L).map { ref(it) }.toSet(), group.sources.flatMap { it.soldiers }.toSet())
        assertEquals(20L, state.generals.getValue(20).generalId)
        assertTrue(state.soldiers.values.all { it.status == SoldierCaptivity.CAPTIVE })
        assertEquals(2L, state.generals.getValue(20).nationId)
    }

    @Test fun `capture replay is idempotent and conflicting or duplicate soldier is rejected`() {
        val state = captured()
        assertEquals(PrisonerCode.ALREADY_COMPLETED, PrisonerRecords.captureSoldiers(state, "capture-a", sourceA, setOf(3, 2, 1), 10).code)
        assertEquals(PrisonerCode.INVALID_REQUEST, PrisonerRecords.captureSoldiers(state, "capture-a", sourceA, setOf(1, 2), 10).code)
        val duplicate = PrisonerRecords.captureSoldiers(state, "other", sourceB, setOf(1, 6), 10)
        assertEquals(PrisonerCode.INVALID_REQUEST, duplicate.code)
        assertEquals(state, duplicate.state)
    }

    @Test fun `unresolved joint captor never invents ownership`() {
        val state = PrisonerState()
        val soldiers = PrisonerRecords.captureSoldiers(state, "a", sourceA, setOf(1), null)
        val general = PrisonerRecords.captureGeneral(state, "b", 20, 2, true, null)
        assertEquals(PrisonerCode.NOT_DECIDED, soldiers.code)
        assertEquals(PrisonerCode.NOT_DECIDED, general.code)
        assertEquals(state, soldiers.state)
        assertEquals(state, general.state)
    }

    @Test fun `release and exchange keep source event trace and conserve captured total`() {
        var state = captured()
        for ((id, status) in listOf(1L to SoldierCaptivity.RELEASED, 4L to SoldierCaptivity.EXCHANGED)) {
            val result = PrisonerRecords.departSoldiers(state, "depart-$id", 10, setOf(ref(id)), status, true)
            assertEquals(PrisonerCode.APPLIED, result.code)
            state = result.state
            assertEquals("depart-$id", state.soldiers.getValue(ref(id)).departureEventId)
            assertEquals(PrisonerCode.ALREADY_COMPLETED, PrisonerRecords.departSoldiers(state, "depart-$id", 10, setOf(ref(id)), status, true).code)
        }
        assertEquals(5, state.soldiers.size)
        assertEquals(3, state.groups().sumOf { it.count })
        assertEquals(2, state.soldiers.values.count { it.departureEventId != null })
        assertEquals(sourceA, state.soldiers.getValue(ref(1)).source)
    }

    @Test fun `unapproved departure has no effect and nonowner is refused`() {
        val state = captured()
        assertEquals(PrisonerCode.NOT_OWNER, PrisonerRecords.departSoldiers(state, "a", 11, setOf(ref(1)), SoldierCaptivity.RELEASED, true).code)
        val result = PrisonerRecords.departSoldiers(state, "b", 10, setOf(ref(1)), SoldierCaptivity.RELEASED, null)
        assertEquals(PrisonerCode.NOT_DECIDED, result.code)
        assertEquals(state, result.state)
        assertEquals(PrisonerCode.INVALID_REQUEST, PrisonerRecords.departSoldiers(state, "c", 10, setOf(ref(1)), SoldierCaptivity.INCORPORATED, true).code)
    }

    @Test fun `deterministic random operation sequence preserves source and prisoner conservation`() {
        fun run(seed: Int): PrisonerState {
            val random = Random(seed)
            var state = captured()
            repeat(100) { index ->
                val id = random.nextLong(1, 6)
                val row = state.soldiers.getValue(ref(id))
                state = PrisonerTransfer.transfer(state, "move-$index", row.ownerGeneralId,
                    if (row.ownerGeneralId == 10L) 11 else 10, setOf(ref(id)), conditionsApproved = true).state
                assertEquals(5, state.groups().sumOf { it.count })
                assertEquals(5, state.soldiers.size)
                assertEquals(mapOf(sourceA to 3, sourceB to 2), state.soldiers.values.groupingBy { it.source }.eachCount())
            }
            return state
        }
        assertEquals(run(123), run(123))
    }
    @Test fun `source identity cannot change origin or revision between capture batches`() {
        val state = captured()
        for (source in listOf(sourceA.copy(originNationId = 3), sourceA.copy(revision = 8))) {
            val result = PrisonerRecords.captureSoldiers(state, "new-capture", source, setOf(6), 10)
            assertEquals(PrisonerCode.INVALID_REQUEST, result.code)
            assertEquals(state, result.state)
        }
    }

    @Test fun `derived view hash does not depend on capture insertion order`() {
        fun snapshot(reverse: Boolean): String {
            var state = PrisonerState()
            val inputs = listOf(sourceA to setOf(1L, 2L), sourceB to setOf(4L, 5L))
            for ((source, ids) in if (reverse) inputs.reversed() else inputs) {
                state = PrisonerRecords.captureSoldiers(state, source.sourceKey, source, ids, 10).state
            }
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(state.groups().toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        assertEquals(snapshot(false), snapshot(true))
    }

}
