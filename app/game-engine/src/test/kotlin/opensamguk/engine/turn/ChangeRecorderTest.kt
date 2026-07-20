package opensamguk.engine.turn

import opensamguk.logic.domain.City as LogicCity
import opensamguk.logic.domain.General as LogicGeneral
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F2 — [ChangeRecorder] is the Immer-`produceWithPatches` replacement and the SINGLE dirty source.
 * It diffs the resolver's pre/post logic [General]/[City] draft to derive (a) the dirty ids and
 * (b) the column patch (only changed columns; for `meta`, only deep-changed keys, insertion order
 * preserved). The resolver NEVER calls the world's updateGeneral/updateCity directly — these
 * patches are the only thing that marks a row dirty (design Risk #4).
 */
class ChangeRecorderTest {

    private fun general(
        id: Int = 1,
        gold: Int = 100,
        experience: Double = 12.0,
        dedication: Double = 34.0,
        politics: Int = 50,
        charm: Int = 50,
        commerce: Int = 0, // unused placeholder for symmetry; ignore
        meta: Map<String, Any?> = linkedMapOf("intel_exp" to 7, "explevel" to 2, "max_domestic_critical" to 0.0),
    ) = LogicGeneral(
        id = id,
        nationId = 1,
        cityId = 5,
        leadership = 80,
        strength = 70,
        intel = 60,
        politics = politics,
        charm = charm,
        injury = 0,
        experience = experience,
        dedication = dedication,
        officerLevel = 0,
        gold = gold,
        rice = 50,
        meta = meta,
    )

    private fun city(
        id: Int = 5,
        commerce: Int = 200,
        agriculture: Int = 100,
        trust: Double = 80.0,
        meta: Map<String, Any?> = linkedMapOf("region" to 3),
    ) = LogicCity(
        id = id,
        nationId = 1,
        level = 5,
        commerce = commerce,
        commerceMax = 2000,
        agriculture = agriculture,
        agricultureMax = 1000,
        supplyState = 1,
        frontState = 0,
        trust = trust,
        meta = meta,
    )

    @Test
    fun `no change yields empty patch and nothing dirty`() {
        val pre = general()
        val recorder = ChangeRecorder()

        val patch = recorder.diffGeneral(pre, pre.copy())

        assertNull(patch, "identical pre/post → null patch (not dirty)")
        assertFalse(recorder.isDirty)
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
        assertTrue(recorder.dirtyCityIds().isEmpty())
        assertTrue(recorder.generalPatches().isEmpty())
    }

    @Test
    fun `no city change yields empty patch and nothing dirty`() {
        val pre = city()
        val recorder = ChangeRecorder()

        val patch = recorder.diffCity(pre, pre.copy())

        assertNull(patch)
        assertFalse(recorder.isDirty)
        assertTrue(recorder.dirtyCityIds().isEmpty())
    }

    @Test
    fun `changed gold experience dedication and meta intel_exp listed exactly`() {
        val pre = general()
        val post = pre.copy(
            gold = 40,
            experience = 26.6,
            dedication = 53.0,
            meta = linkedMapOf("intel_exp" to 8, "explevel" to 2, "max_domestic_critical" to 0.0),
        )
        val recorder = ChangeRecorder()

        val patch = recorder.diffGeneral(pre, post)!!

        // exactly the changed scalar columns (unchanged columns absent)
        assertEquals(setOf("gold", "experience", "dedication"), patch.columns.keys)
        assertEquals(40, patch.columns["gold"])
        assertEquals(26.6, patch.columns["experience"])
        assertEquals(53.0, patch.columns["dedication"])

        // only the deep-changed meta key is in the meta patch; unchanged keys absent
        assertEquals(setOf("intel_exp"), patch.meta.keys, "explevel/max_domestic_critical unchanged → absent")
        assertEquals(8, patch.meta["intel_exp"])

        // recorder marks the general dirty (the SINGLE dirty source)
        assertTrue(recorder.isDirty)
        assertEquals(setOf(1), recorder.dirtyGeneralIds())
        assertTrue(recorder.dirtyCityIds().isEmpty())
        assertEquals(patch, recorder.generalPatches().single())
        assertEquals(1, patch.id)
    }

    @Test
    fun `changed politics and charm mark the general dirty`() {
        val pre = general(politics = 71, charm = 82)
        val recorder = ChangeRecorder()

        val patch = recorder.diffGeneral(pre, pre.copy(politics = 72, charm = 83))!!

        assertEquals(setOf("politics", "charm"), patch.columns.keys)
        assertEquals(72, patch.columns["politics"])
        assertEquals(83, patch.columns["charm"])
        assertEquals(setOf(1), recorder.dirtyGeneralIds())
    }

    @Test
    fun `kv observer keeps live game and nation env in sync`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH),
                nations = listOf(Nation(1, "촉", "#00ff00")),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(1, 200, 1, 3600, Instant.EPOCH)).id),
            ),
        )
        val recorder = ChangeRecorder(kvWriteObserver = world::applyKvDirtyFree)

        recorder.recordKv("game_env", "game_env", "tournament", 1)
        recorder.recordNationEnvKv(1, "available_war_setting_cnt", 2)

        assertEquals(1, world.getState().meta["tournament"])
        assertEquals(
            2,
            (world.getNationById(1)!!.meta["nation_env"] as Map<*, *>)["available_war_setting_cnt"],
        )
    }

    @Test
    fun `changed commerce listed for city`() {
        val pre = city()
        val post = pre.copy(commerce = 250)
        val recorder = ChangeRecorder()

        val patch = recorder.diffCity(pre, post)!!

        assertEquals(setOf("commerce"), patch.columns.keys)
        assertEquals(250, patch.columns["commerce"])
        assertTrue(patch.meta.isEmpty(), "city meta unchanged → empty meta patch")
        assertEquals(5, patch.id)
        assertTrue(recorder.isDirty)
        assertEquals(setOf(5), recorder.dirtyCityIds())
    }

    @Test
    fun `changed agriculture listed for city`() {
        val pre = city()
        val post = pre.copy(agriculture = 175)
        val recorder = ChangeRecorder()

        val patch = recorder.diffCity(pre, post)!!

        assertEquals(setOf("agriculture"), patch.columns.keys)
        assertEquals(175, patch.columns["agriculture"])
    }

    @Test
    fun `successive patches for one city preserve earlier columns`() {
        val pre = city()
        val recorder = ChangeRecorder()

        val mid = pre.copy(state = 43, term = 3)
        recorder.diffCity(pre, mid)
        recorder.diffCity(mid, mid.copy(term = 0))

        val patch = recorder.cityPatches().single()
        assertEquals(43, patch.columns["state"])
        assertEquals(0, patch.columns["term"])
    }

    @Test
    fun `W0-8 -- 재해 state 변경이 diffCity에 잡혀 도시가 dirty 마킹된다`() {
        // RaiseDisaster: 무조건 리셋(state<=10→0)과 선택 도시 stateCode(1~9) 기록 둘 다
        // state-only 변경일 수 있다 — diffCity가 state를 비교하지 않으면 recorder-flush에서 유실(P0-36).
        val pre = city()
        val recorder = ChangeRecorder()

        val patch = recorder.diffCity(pre, pre.copy(state = 7))!!

        assertEquals(setOf("state"), patch.columns.keys)
        assertEquals(7, patch.columns["state"])
        assertEquals(setOf(5), recorder.dirtyCityIds())
    }

    @Test
    fun `unchanged meta keys are not in the patch`() {
        // only max_domestic_critical changes; intel_exp + explevel stay
        val pre = general()
        val post = pre.copy(
            meta = linkedMapOf("intel_exp" to 7, "explevel" to 2, "max_domestic_critical" to 1200.5),
        )
        val recorder = ChangeRecorder()

        val patch = recorder.diffGeneral(pre, post)!!

        assertTrue(patch.columns.isEmpty(), "no scalar column changed")
        assertEquals(setOf("max_domestic_critical"), patch.meta.keys)
        assertEquals(1200.5, patch.meta["max_domestic_critical"])
        // the recorder is still dirty because a meta key changed
        assertTrue(recorder.isDirty)
        assertEquals(setOf(1), recorder.dirtyGeneralIds())
    }

    @Test
    fun `meta key insertion order preserved in the patch`() {
        // change intel_exp (1st) and max_domestic_critical (3rd); skip explevel (2nd).
        // The patch must list changed keys in the post-state's insertion order: intel_exp, max_domestic_critical.
        val pre = general()
        val post = pre.copy(
            meta = linkedMapOf("intel_exp" to 8, "explevel" to 2, "max_domestic_critical" to 600.0),
        )
        val recorder = ChangeRecorder()

        val patch = recorder.diffGeneral(pre, post)!!

        assertEquals(listOf("intel_exp", "max_domestic_critical"), patch.meta.keys.toList(),
            "meta patch preserves post-state insertion order, skipping the unchanged key")
    }

    @Test
    fun `newly added meta key is recorded as a change`() {
        val pre = general(meta = linkedMapOf("intel_exp" to 7))
        val post = general(meta = linkedMapOf("intel_exp" to 7, "killturn" to 3))
        val recorder = ChangeRecorder()

        val patch = recorder.diffGeneral(pre, post)!!

        assertEquals(setOf("killturn"), patch.meta.keys)
        assertEquals(3, patch.meta["killturn"])
    }

    @Test
    fun `record marks dirty across multiple generals and cities`() {
        val g1 = general(id = 1)
        val g2 = general(id = 2, gold = 500)
        val c5 = city(id = 5)
        val recorder = ChangeRecorder()

        recorder.diffGeneral(g1, g1.copy(gold = 10))      // g1 changed
        recorder.diffGeneral(g2, g2.copy())               // g2 unchanged
        recorder.diffCity(c5, c5.copy(commerce = 999))    // c5 changed

        assertEquals(setOf(1), recorder.dirtyGeneralIds(), "only changed generals are dirty")
        assertEquals(setOf(5), recorder.dirtyCityIds())
        assertEquals(2, recorder.generalPatches().size + recorder.cityPatches().size)
    }

    @Test
    fun `vote_poll UPDATE channel merges columns per poll last-write-wins and preserves order`() {
        val recorder = ChangeRecorder()

        // 1차: 설문 7 닫기 — end_at 기록.
        recorder.recordVotePollUpdate(7, linkedMapOf("end_at" to "2026-06-03 12:00:00"))
        // 2차: 같은 설문에 closed_at + updated_at 추가 — 컬럼 단위 병합(end_at 유지).
        recorder.recordVotePollUpdate(
            7,
            linkedMapOf("closed_at" to "2026-06-03 12:00:01", "updated_at" to "2026-06-03 12:00:01"),
        )
        // 3차: end_at 재기록 — 같은 컬럼 last-write-wins(나중 값이 이긴다).
        recorder.recordVotePollUpdate(7, linkedMapOf("end_at" to "2026-06-03 12:30:00"))
        // 다른 설문 9 — 별개 행.
        recorder.recordVotePollUpdate(9, linkedMapOf("end_at" to "2026-06-03 13:00:00"))

        assertTrue(recorder.isDirty, "vote_poll UPDATE 기록됨 → dirty")
        val updates = recorder.votePollUpdates()
        assertEquals(listOf(7, 9), updates.keys.toList(), "행 키 삽입 순서 보존")

        val poll7 = updates.getValue(7)
        // 컬럼 단위 병합: end_at(재기록 값) + closed_at + updated_at 모두 존재, 삽입 순서 보존.
        assertEquals(listOf("end_at", "closed_at", "updated_at"), poll7.keys.toList())
        assertEquals("2026-06-03 12:30:00", poll7["end_at"], "같은 컬럼 last-write-wins")
        assertEquals("2026-06-03 12:00:01", poll7["closed_at"])
        assertEquals("2026-06-03 12:00:01", poll7["updated_at"])
        assertEquals(mapOf("end_at" to "2026-06-03 13:00:00"), updates.getValue(9))

        // 방어적 복사: 반환된 맵을 변경해도 내부 상태는 불변.
        (poll7 as MutableMap)["end_at"] = "tampered"
        assertEquals("2026-06-03 12:30:00", recorder.votePollUpdates().getValue(7)["end_at"])

        recorder.clear()
        assertFalse(recorder.isDirty, "clear() 후 dirty 아님")
        assertTrue(recorder.votePollUpdates().isEmpty(), "vote_poll UPDATE는 다음 tick으로 넘어가면 안 된다")
    }

    @Test
    fun `clear empties every channel so the long-lived recorder does not re-emit deltas next tick`() {
        val recorder = ChangeRecorder()
        recorder.diffGeneral(general(id = 1), general(id = 1, gold = 10))   // UPDATE/patch 채널
        recorder.recordBettingInsert(linkedMapOf("betting_id" to 1, "general_id" to 1))  // INSERT 전용 채널
        recorder.recordBoardPostInsert(linkedMapOf("nation_id" to 1, "title" to "t"))    // 게시판 INSERT 전용
        recorder.recordBoardCommentInsert(linkedMapOf("post_id" to 1, "content_text" to "c"))
        recorder.recordYearbookInsert(linkedMapOf("profile_name" to "sc", "year" to 181, "month" to 1)) // W0-8 연감 채널
        assertTrue(recorder.isDirty, "채널 기록됨 → dirty")

        recorder.clear()

        // 모든 채널이 비워짐 → clear 후 재-flush는 아무것도 방출하지 않는다(다음 tick INSERT 중복 없음).
        assertFalse(recorder.isDirty, "clear()가 dirty 플래그를 리셋한다")
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
        assertTrue(recorder.bettingInserts().isEmpty())
        assertTrue(recorder.boardPostInserts().isEmpty(), "board_post INSERT는 다음 tick으로 넘어가면 안 된다")
        assertTrue(recorder.boardCommentInserts().isEmpty(), "board_comment INSERT는 다음 tick으로 넘어가면 안 된다")
        assertTrue(recorder.yearbookInserts().isEmpty(), "yearbook UPSERT는 다음 tick으로 넘어가면 안 된다")
    }

    @Test
    fun `W0-8 -- yearbook 채널은 emit 순서를 보존하고 단독으로도 dirty 신호를 세운다`() {
        // 연감 채널만 기록된 tick(월별 LogHistory 스냅샷)에서 flush 트리거가 서지 않으면
        // 스냅샷이 조용히 유실된다 — isDirty가 yearbook 단독으로도 true여야 한다.
        val recorder = ChangeRecorder()
        assertFalse(recorder.isDirty)

        recorder.recordYearbookInsert(linkedMapOf("profile_name" to "sc", "year" to 181, "month" to 1))
        recorder.recordYearbookInsert(linkedMapOf("profile_name" to "sc", "year" to 181, "month" to 2))

        assertTrue(recorder.isDirty, "yearbook 단독 기록 → dirty (flush 트리거)")
        val rows = recorder.yearbookInserts()
        assertEquals(2, rows.size)
        assertEquals(1, rows[0].columns["month"], "emit 순서 보존 — 1월 먼저")
        assertEquals(2, rows[1].columns["month"])
    }
}
