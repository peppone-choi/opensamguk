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
    fun `a dead flip is carried and state-term last-write-wins per from-to key`() {
        val recorder = ChangeRecorder()
        recorder.diffDiplomacy(dip(1, 2, state = 1, dead = 0), dip(1, 2, state = 1, dead = 5))
        // a later transition for the same key wins on state/term (both are always full post-state)...
        recorder.diffDiplomacy(dip(1, 2, state = 1, dead = 5), dip(1, 2, state = 0, term = 0, dead = 5))

        val patch = recorder.diplomacyUpdateDirty().single()
        assertEquals(0, patch.state, "last write wins")
        // ...but MUST NOT erase the casualty column the earlier patch recorded (OPENSAM-176).
        assertEquals(5, patch.dead, "the second diff did not change dead → the earlier dead survives")
    }

    /**
     * OPENSAM-176 — column-wise, not row-wise, last-write-wins. Same tick: 전투가 casualty를 기록한 뒤
     * 월간 Q9 정산이 같은 쌍의 state/term만 바꾸면 `dead = null`을 실은 패치가 앞선 casualty를 통째로
     * 덮었다. `JdbcFlushExecutor.diplomacyUpdate`는 `dead == null`이면 `casualties`를 SET하지 않으므로
     * 인메모리에는 있고 DB에는 영영 안 가는 조용한 유실이 된다.
     */
    @Test
    fun `battle casualty survives a later same-tick patch that does not change dead`() {
        val recorder = ChangeRecorder()
        // ① 전투: casualty 기록 (dead 0 -> 15)
        recorder.diffDiplomacy(dip(1, 2, state = 1, term = 24, dead = 0), dip(1, 2, state = 1, term = 24, dead = 15))
        // ② 월간 Q9: 같은 쌍의 term만 감소, dead 불변
        recorder.diffDiplomacy(dip(1, 2, state = 1, term = 24, dead = 15), dip(1, 2, state = 1, term = 23, dead = 15))

        val patch = recorder.diplomacyUpdateDirty().single()
        assertEquals(23, patch.term, "state/term은 나중 패치가 이긴다")
        assertEquals(15, patch.dead, "casualty가 flush 페이로드에서 유실되면 안 된다")
    }

    @Test
    fun `casualty survives a chain of three patches whose middle patch leaves dead alone`() {
        val recorder = ChangeRecorder()
        // ① 전투: casualty 기록 (dead 0 -> 12)
        recorder.diffDiplomacy(dip(1, 2, state = 0, term = 24, dead = 0), dip(1, 2, state = 0, term = 24, dead = 12))
        // ② 중간 패치: term만 변함 — dead를 안 건드린다.
        recorder.diffDiplomacy(dip(1, 2, state = 0, term = 24, dead = 12), dip(1, 2, state = 0, term = 23, dead = 12))
        // ③ 세 번째 패치도 dead를 안 건드린다 — 상속이 한 단계가 아니라 체인 끝까지 이어져야 한다.
        recorder.diffDiplomacy(dip(1, 2, state = 0, term = 23, dead = 12), dip(1, 2, state = 0, term = 22, dead = 12))

        val patch = recorder.diplomacyUpdateDirty().single()
        assertEquals(22, patch.term, "state/term은 마지막 패치가 이긴다")
        assertEquals(12, patch.dead, "최초 casualty가 체인 끝까지 살아남아야 한다")
    }

    /**
     * `PostUpdateMonthly.kt:301` — Q9의 `deadReset = if (d.state != 0) 0 else d.dead`. 교전이 끝나(state != 0)
     * casualty가 0으로 리셋되는 패치는 dead가 실제로 변하는 패치이므로, 상속이 아니라 `post.dead = 0`이 이겨야 한다.
     */
    @Test
    fun `a Q9 dead reset to zero wins over the inherited casualty`() {
        val recorder = ChangeRecorder()
        // ① 전투 중 casualty 기록 (state=0 교전, dead 0 -> 15)
        recorder.diffDiplomacy(dip(1, 2, state = 0, term = 24, dead = 0), dip(1, 2, state = 0, term = 24, dead = 15))
        // ② Q7 종전(state 0 -> 2) + Q9 deadReset: state != 0 이므로 dead 15 -> 0.
        recorder.diffDiplomacy(dip(1, 2, state = 0, term = 24, dead = 15), dip(1, 2, state = 2, term = 0, dead = 0))

        val patch = recorder.diplomacyUpdateDirty().single()
        assertEquals(2, patch.state)
        assertEquals(0, patch.dead, "리셋은 실제 변경이므로 상속이 덮어쓰면 안 된다")
    }

    @Test
    fun `key insertion order is preserved when an earlier key is re-patched`() {
        val recorder = ChangeRecorder()
        recorder.diffDiplomacy(dip(1, 2, state = 2), dip(1, 2, state = 1, term = 24, dead = 7))
        recorder.diffDiplomacy(dip(2, 1, state = 2), dip(2, 1, state = 1, term = 24))
        recorder.diffDiplomacy(dip(3, 4, state = 2), dip(3, 4, state = 1, term = 24))
        // re-patch the FIRST key: it must keep its original slot, not move to the tail.
        recorder.diffDiplomacy(dip(1, 2, state = 1, term = 24, dead = 7), dip(1, 2, state = 0, term = 0, dead = 7))

        val patches = recorder.diplomacyUpdateDirty()
        assertEquals(
            listOf(1 to 2, 2 to 1, 3 to 4),
            patches.map { it.fromNationId to it.toNationId },
            "삽입 순서 보존은 패리티 계약이다",
        )
        assertEquals(7, patches.first().dead, "re-patch가 앞선 dead를 지우면 안 된다")
        assertEquals(0, patches.first().state)
    }
}
