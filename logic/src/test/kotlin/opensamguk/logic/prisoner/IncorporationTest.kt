package opensamguk.logic.prisoner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IncorporationTest {
    private val terms = IncorporationTerms(mapOf("grain" to 4, "gold" to 2), 10)
    private fun initial() = captured().copy(stocks = mapOf(10L to mapOf("grain" to 10L, "gold" to 10L)))

    @Test fun `soldiers reserve resources once and only join after retraining`() {
        val queued = Incorporation.enqueue(initial(), "train", 10, setOf(ref(1), ref(4)), 5, terms)
        assertEquals(PrisonerCode.APPLIED, queued.code)
        assertEquals(mapOf("grain" to 6L, "gold" to 8L), queued.state.stocks[10])
        assertEquals(5, queued.state.groups().sumOf { it.count })
        assertEquals(0, queued.state.soldiers.values.count { it.status == SoldierCaptivity.INCORPORATED })
        assertEquals(PrisonerCode.ALREADY_COMPLETED, Incorporation.enqueue(queued.state, "train", 10, setOf(ref(4), ref(1)), 5, terms).code)
        assertEquals(PrisonerCode.NOT_READY, Incorporation.complete(queued.state, "train", 10, 14, true).code)
        assertEquals(PrisonerCode.NOT_DECIDED, Incorporation.complete(queued.state, "train", 10, 15, null).code)
        assertEquals(PrisonerCode.NOT_DECIDED, Incorporation.complete(queued.state, "train", 10, 15, false).code)
        val completed = Incorporation.complete(queued.state, "train", 10, 15, true)
        assertEquals(PrisonerCode.APPLIED, completed.code)
        assertEquals(3, completed.state.groups().sumOf { it.count })
        val joined = completed.state.soldiers.values.filter { it.status == SoldierCaptivity.INCORPORATED }
        assertEquals(2, joined.size)
        assertEquals(setOf(sourceA, sourceB), joined.map { it.source }.toSet())
        assertEquals(setOf("train"), joined.map { it.departureEventId }.toSet())
        assertEquals(PrisonerCode.ALREADY_COMPLETED, Incorporation.complete(completed.state, "train", 10, 20, true).code)
    }

    @Test fun `missing cost or duration and cancellation never invent policy`() {
        val state = initial()
        for (quote in listOf(IncorporationTerms(), IncorporationTerms(mapOf("grain" to 1)), IncorporationTerms(trainingTicks = 10))) {
            val result = Incorporation.enqueue(state, "train", 10, setOf(ref(1)), 0, quote)
            assertEquals(PrisonerCode.NOT_DECIDED, result.code)
            assertEquals(state, result.state)
        }
        assertEquals(PrisonerCode.NOT_DECIDED, Incorporation.cancel(state).code)
        assertEquals(PrisonerCode.ACTION_NOT_ALLOWED, Incorporation.enqueueGeneral(state, 20).code)
    }

    @Test fun `stock ownership and overflow failures are atomic`() {
        val state = initial()
        assertEquals(PrisonerCode.INSUFFICIENT_STOCK,
            Incorporation.enqueue(state, "a", 10, setOf(ref(1)), 0, IncorporationTerms(mapOf("grain" to 11), 10)).code)
        assertEquals(PrisonerCode.NOT_OWNER, Incorporation.enqueue(state, "b", 11, setOf(ref(1)), 0, terms).code)
        assertEquals(PrisonerCode.INVALID_REQUEST, Incorporation.enqueue(state, "c", 10, setOf(ref(1)), Long.MAX_VALUE, terms).code)
        assertFailsWith<IllegalArgumentException> { IncorporationTerms(emptyMap(), 10) }
        assertFailsWith<IllegalArgumentException> { IncorporationTerms(mapOf("grain" to 1), 0) }
        val queued = Incorporation.enqueue(state, "d", 10, setOf(ref(1)), 0, terms).state
        assertEquals(PrisonerCode.ACTION_NOT_ALLOWED, Incorporation.enqueue(queued, "e", 10, setOf(ref(1)), 0, terms).code)
        assertEquals(PrisonerCode.NOT_OWNER, Incorporation.complete(queued, "d", 11, 10, true).code)
    }

    @Test fun `player persuasion offers cannot change nation without own acceptance`() {
        val initial = captured()
        val offered = PrisonerRecruitment.offer(initial, "offer", 10, 20, 3, true)
        assertEquals(PrisonerCode.APPLIED, offered.code)
        assertEquals(initial.generals, offered.state.generals)
        assertEquals(PrisonerCode.NOT_OWNER, PrisonerRecruitment.reply(offered.state, "offer", 10, true).code)
        val accepted = PrisonerRecruitment.reply(offered.state, "offer", 20, true)
        assertEquals(3L, accepted.state.generals.getValue(20).nationId)
        assertEquals(GeneralCaptivity.RECRUITED, accepted.state.generals.getValue(20).status)
        assertEquals(PrisonerCode.ALREADY_COMPLETED, PrisonerRecruitment.reply(accepted.state, "offer", 20, true).code)
        assertEquals(PrisonerCode.INVALID_REQUEST, PrisonerRecruitment.reply(accepted.state, "offer", 20, false).code)
    }

    @Test fun `rejection silence and NPC persuasion preserve nation and existing path`() {
        val initial = captured()
        val offered = PrisonerRecruitment.offer(initial, "offer", 10, 20, 3, true).state
        val rejected = PrisonerRecruitment.reply(offered, "offer", 20, false)
        assertEquals(initial.generals, rejected.state.generals)
        assertEquals(initial.generals, offered.generals)
        val npc = PrisonerRecords.captureGeneral(initial, "npc", 21, 2, false, 10).state
        val result = PrisonerRecruitment.offer(npc, "offer-npc", 10, 21, 3, true)
        assertEquals(PrisonerCode.NOT_DECIDED, result.code)
        assertEquals(npc, result.state)
        assertEquals(PrisonerCode.ACTION_NOT_ALLOWED, Incorporation.enqueueGeneral(npc, 21).code)
    }

    @Test fun `offer from old custodian cannot recruit after transfer`() {
        val offered = PrisonerRecruitment.offer(captured(), "offer", 10, 20, 3, true).state
        val moved = PrisonerTransfer.transfer(offered, "move", 10, 11, generalIds = setOf(20), conditionsApproved = true).state
        assertEquals(PrisonerCode.ACTION_NOT_ALLOWED, PrisonerRecruitment.reply(moved, "offer", 20, true).code)
        assertEquals(2L, moved.generals.getValue(20).nationId)
    }
    @Test fun `event receipt does not confuse delimiter containing resource names`() {
        val state = initial().copy(stocks = mapOf(10L to mapOf("a" to 10L, "b" to 10L, "a=1, b" to 10L)))
        val first = Incorporation.enqueue(state, "train", 10, setOf(ref(1)), 0, IncorporationTerms(mapOf("a" to 1, "b" to 2), 10))
        val conflicting = Incorporation.enqueue(first.state, "train", 10, setOf(ref(1)), 0, IncorporationTerms(mapOf("a=1, b" to 2), 10))
        assertEquals(PrisonerCode.INVALID_REQUEST, conflicting.code)
        assertEquals(first.state, conflicting.state)
    }

}
