package opensamguk.engine.turn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T0.4 — the diplomacy UPDATE delta channel on the SINGLE dirty source.
 *
 * `che_선전포고`/`수락`/`파기`/`종전` toggle the bidirectional diplomacy state; the in-memory port MUST
 * update BOTH `(me,you)` + `(you,me)` (PHP `(me=A AND you=B) OR (me=B AND you=A)`) — missing one
 * desyncs the matrix + the next tick's me<you/both-dir-state logic. This channel is DISTINCT from the
 * monthly TICK's bulk-SQL diplomacy update (P3 PostUpdateMonthly).
 */
class DiffDiplomacyTest {

    private fun dip(from: Int, to: Int, state: Int, term: Int = 0, dead: Int = 0) =
        TurnDiplomacy(fromNationId = from, toNationId = to, state = state, term = term, dead = dead)

    @Test
    fun `no diplomacy change yields null patch and nothing dirty`() {
        val recorder = ChangeRecorder()
        val pre = dip(1, 2, state = 2)
        assertNull(recorder.diffDiplomacy(pre, pre.copy()))
        assertTrue(recorder.diplomacyUpdateDirty().isEmpty())
    }

    @Test
    fun `bidirectional 선전포고 emits BOTH directions (state 2 to 1, term to 24)`() {
        val recorder = ChangeRecorder()
        // me->you and you->me both transition (선전포고: state=1/term=24 on both rows).
        recorder.diffDiplomacy(dip(1, 2, state = 2), dip(1, 2, state = 1, term = 24))
        recorder.diffDiplomacy(dip(2, 1, state = 2), dip(2, 1, state = 1, term = 24))

        val patches = recorder.diplomacyUpdateDirty()
        assertEquals(2, patches.size, "BOTH (me,you) and (you,me) must be patched")
        assertEquals(listOf(1 to 2, 2 to 1), patches.map { it.fromNationId to it.toNationId })
        assertTrue(patches.all { it.state == 1 && it.term == 24 })
        assertTrue(patches.all { it.dead == null }, "dead unchanged → not carried in the patch")
    }

    @Test
    fun `a dead flip is carried and last-write-wins per from-to key`() {
        val recorder = ChangeRecorder()
        recorder.diffDiplomacy(dip(1, 2, state = 1, dead = 0), dip(1, 2, state = 1, dead = 5))
        // a later transition for the same key displaces the earlier patch.
        recorder.diffDiplomacy(dip(1, 2, state = 1, dead = 5), dip(1, 2, state = 0, term = 0, dead = 5))

        val patch = recorder.diplomacyUpdateDirty().single()
        assertEquals(0, patch.state, "last write wins")
        assertNull(patch.dead, "the second diff did not change dead, so it is not carried")
    }
}
